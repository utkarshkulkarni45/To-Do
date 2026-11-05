package com.todo.app.service;

import com.todo.app.dto.PageResponse;
import com.todo.app.dto.ProjectRequest;
import com.todo.app.dto.ProjectView;
import com.todo.app.entity.Project;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.ProjectRepository;
import com.todo.app.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final Set<String> SORTABLE = Set.of("name", "createdAt", "updatedAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final ReferenceMapper mapper;

    public ProjectService(ProjectRepository projectRepository, TaskRepository taskRepository, ReferenceMapper mapper) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.mapper = mapper;
    }

    /**
     * Paged by the database. The open-task counts for the returned page come from one grouped query
     * rather than a count per project.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProjectView> list(long ownerId, int page, int size, String sortProperty) {
        String property = SORTABLE.contains(sortProperty) ? sortProperty : "updatedAt";
        var pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Direction.ASC, property));
        var projects = projectRepository.findByOwnerIdAndDeletedFalse(ownerId, pageable);
        Map<Long, Long> openCounts = openTaskCounts(ownerId,
                projects.getContent().stream().map(Project::getId).collect(Collectors.toSet()));
        return PageResponse.from(projects.map(project ->
                mapper.toView(project, openCounts.getOrDefault(project.getId(), 0L))));
    }

    @Transactional
    public ProjectView create(long ownerId, ProjectRequest request) {
        String name = request.name().trim();
        if (projectRepository.existsByOwnerIdAndNameIgnoreCaseAndDeletedFalse(ownerId, name)) {
            throw ApiException.conflict(ErrorCodes.CONFLICT, "You already have a project called " + name);
        }
        Project project = new Project(ownerId, name, blankToNull(request.description()), request.color());
        projectRepository.saveAndFlush(project);
        log.info("Created project id={} owner={}", project.getId(), ownerId);
        return mapper.toView(project, 0);
    }

    @Transactional
    public ProjectView update(long ownerId, long projectId, ProjectRequest request) {
        Project project = load(ownerId, projectId);
        String name = request.name().trim();
        if (!project.getName().equalsIgnoreCase(name)
                && projectRepository.existsByOwnerIdAndNameIgnoreCaseAndDeletedFalse(ownerId, name)) {
            throw ApiException.conflict(ErrorCodes.CONFLICT, "You already have a project called " + name);
        }
        VersionedWrites.requireUnchanged("project", project.getVersion(), request.version());
        project.setName(name);
        project.setDescription(blankToNull(request.description()));
        project.setColor(request.color());
        return mapper.toView(persist(project),
                taskRepository.countOpenTasksInProject(ownerId, projectId));
    }

    /**
     * Removing a project does not remove its tasks: they are detached in one statement and become
     * unassigned. A project that still holds open tasks needs the explicit force flag, so that one
     * careless click cannot hide work in progress from the task list.
     */
    @Transactional
    public void delete(long ownerId, long projectId, boolean force) {
        Project project = load(ownerId, projectId);
        long openTasks = taskRepository.countOpenTasksInProject(ownerId, projectId);
        if (openTasks > 0 && !force) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                    "Project still has " + openTasks + " open tasks. Retry with force=true to detach them.");
        }
        project.setDeleted(true);
        persist(project);
        int detached = taskRepository.detachFromProject(projectId);
        log.info("Deleted project id={} owner={}, detached {} tasks", projectId, ownerId, detached);
    }

    private Map<Long, Long> openTaskCounts(long ownerId, Set<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = projectRepository.countOpenTasksForProjects(ownerId, projectIds);
        return rows.stream().collect(Collectors.toMap(row -> (Long) row[0], row -> ((Number) row[1]).longValue()));
    }

    private Project load(long ownerId, long projectId) {
        return projectRepository.findByIdAndOwnerIdAndDeletedFalse(projectId, ownerId)
                .orElseThrow(() -> ApiException.notFound("Project", projectId));
    }

    private Project persist(Project project) {
        try {
            return projectRepository.saveAndFlush(project);
        } catch (ObjectOptimisticLockingFailureException conflict) {
            throw VersionedWrites.conflict("project");
        }
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
