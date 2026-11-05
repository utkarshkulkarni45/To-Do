package com.todo.app.service;

import com.todo.app.config.CacheConfig;
import com.todo.app.dto.PageResponse;
import com.todo.app.dto.TaskBulkStatusRequest;
import com.todo.app.dto.TaskPatchRequest;
import com.todo.app.dto.TaskQuery;
import com.todo.app.dto.TaskStatsView;
import com.todo.app.dto.TaskUpsertRequest;
import com.todo.app.dto.TaskView;
import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.entity.Task;
import com.todo.app.enums.TaskStatus;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.TaskMapper;
import com.todo.app.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final Set<String> SORTABLE = Set.of("dueAt", "createdAt", "updatedAt", "title", "status", "priority");
    private static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository taskRepository;
    private final ReferenceResolver references;
    private final TaskMapper mapper;

    public TaskService(TaskRepository taskRepository, ReferenceResolver references, TaskMapper mapper) {
        this.taskRepository = taskRepository;
        this.references = references;
        this.mapper = mapper;
    }

    @Transactional
    public TaskView create(long ownerId, TaskUpsertRequest request) {
        Instant now = Instant.now();
        Task task = new Task(ownerId, request.title().trim());
        task.setDescription(blankToNull(request.description()));
        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setDueAt(request.dueAt());
        task.setProject(references.resolveProject(ownerId, request.projectId()));
        if (request.status() == TaskStatus.DONE) {
            task.setCompletedAt(now);
        }
        Set<Tag> tags = references.resolveTags(ownerId, request.tags());
        if (tags != null) {
            task.setTags(tags);
        }
        taskRepository.saveAndFlush(task);
        log.info("Created task id={} owner={} status={}", task.getId(), ownerId, task.getStatus());
        return mapper.toView(task, now);
    }

    @Transactional(readOnly = true)
    public TaskView find(long ownerId, long taskId) {
        Instant now = Instant.now();
        return mapper.toView(loadVisible(ownerId, taskId), now);
    }

    /**
     * The listing endpoint. Filtering happens in SQL so that a page is fetched with two statements:
     * one for the rows (project joined in) and one for the count.
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskView> search(long ownerId, TaskQuery query) {
        var pageable = PageRequest.of(Math.max(0, query.page()), clampSize(query.size()), sortFor(query));
        String keyword = query.keyword() == null ? null : "%" + query.keyword().toLowerCase(Locale.ROOT) + "%";
        var page = taskRepository.findVisibleWithProject(ownerId, query.status(), query.priority(),
                query.projectId(), query.tagId(), query.overdue(), keyword, Instant.now(), pageable);
        Instant now = Instant.now();
        return PageResponse.from(page.map(task -> mapper.toView(task, now)));
    }

    @Transactional
    public TaskView update(long ownerId, long taskId, TaskUpsertRequest request) {
        Instant now = Instant.now();
        Task task = loadVisible(ownerId, taskId);
        requireCurrentVersion(task, request.version());
        task.setTitle(request.title().trim());
        task.setDescription(blankToNull(request.description()));
        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setDueAt(request.dueAt());
        task.setProject(references.resolveProject(ownerId, request.projectId()));
        applyCompletion(task, now);
        Set<Tag> tags = references.resolveTags(ownerId, request.tags());
        if (tags != null) {
            task.setTags(tags);
        }
        return mapper.toView(save(task), now);
    }

    /** Partial change used by the UI's status toggle, which must not rewrite the rest of the task. */
    @Transactional
    public TaskView patch(long ownerId, long taskId, TaskPatchRequest request) {
        Instant now = Instant.now();
        Task task = loadVisible(ownerId, taskId);
        requireCurrentVersion(task, request.version());
        if (request.title() != null && !request.title().isBlank()) {
            task.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            task.setDescription(blankToNull(request.description()));
        }
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.dueAtSet()) {
            task.setDueAt(request.dueAt());
        }
        if (request.projectIdSet()) {
            task.setProject(references.resolveProject(ownerId, request.projectId()));
        }
        applyCompletion(task, now);
        return mapper.toView(save(task), now);
    }

    @Transactional
    public List<TaskView> bulkStatus(long ownerId, TaskBulkStatusRequest request) {
        Instant now = Instant.now();
        List<Task> tasks = taskRepository.findByIdInAndOwnerIdAndDeletedFalse(request.ids(), ownerId);
        long requested = request.ids().stream().distinct().count();
        if (tasks.size() != requested) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND,
                    (requested - tasks.size()) + " of the listed task ids are not visible to this account");
        }
        tasks.forEach(task -> {
            task.setStatus(request.status());
            applyCompletion(task, now);
        });
        taskRepository.saveAll(tasks);
        log.info("Set status {} on {} tasks of owner {}", request.status(), tasks.size(), ownerId);
        return tasks.stream().map(task -> mapper.toView(task, now)).toList();
    }

    /** Soft delete: the row stays for audit and for undo, and disappears from every read path. */
    @Transactional
    public void delete(long ownerId, long taskId) {
        Task task = loadVisible(ownerId, taskId);
        task.setDeleted(true);
        save(task);
        log.info("Soft-deleted task id={} owner={}", taskId, ownerId);
    }

    /**
     * Counts for the dashboard header. Cached for 120 seconds (see CacheConfig): the numbers are
     * advisory, and a five-aggregate endpoint should not reach the database on every page view.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.TASK_STATS_CACHE, key = "#ownerId")
    public TaskStatsView stats(long ownerId) {
        Instant now = Instant.now();
        long todo = 0;
        long inProgress = 0;
        long done = 0;
        for (Object[] row : taskRepository.countByStatus(ownerId)) {
            switch ((TaskStatus) row[0]) {
                case TODO -> todo = ((Number) row[1]).longValue();
                case IN_PROGRESS -> inProgress = ((Number) row[1]).longValue();
                case DONE -> done = ((Number) row[1]).longValue();
            }
        }
        return new TaskStatsView(todo + inProgress + done, todo, inProgress, done,
                taskRepository.countOverdue(ownerId, now));
    }

    private Task loadVisible(long ownerId, long taskId) {
        return taskRepository.findVisibleById(taskId, ownerId)
                .orElseThrow(() -> ApiException.notFound("Task", taskId));
    }

    private void applyCompletion(Task task, Instant now) {
        if (task.getStatus() == TaskStatus.DONE) {
            if (task.getCompletedAt() == null) {
                task.setCompletedAt(now);
            }
        } else {
            task.setCompletedAt(null);
        }
    }

    private Task save(Task task) {
        try {
            // Flushing here means the version in the response is the one the row actually has after
            // this write, and a concurrent change is reported instead of surfacing at commit time.
            return taskRepository.saveAndFlush(task);
        } catch (ObjectOptimisticLockingFailureException conflict) {
            throw VersionedWrites.conflict("task");
        }
    }

    private void requireCurrentVersion(Task task, Long versionReadByClient) {
        VersionedWrites.requireUnchanged("task", task.getVersion(), versionReadByClient);
    }

    private Sort sortFor(TaskQuery query) {
        String property = SORTABLE.contains(query.sortProperty()) ? query.sortProperty() : "dueAt";
        Sort.Direction direction = query.sortDescending() ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort.NullHandling nulls = direction == Sort.Direction.ASC ? Sort.NullHandling.NULLS_LAST : Sort.NullHandling.NULLS_FIRST;
        return Sort.by(new Sort.Order(direction, property, nulls)).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
