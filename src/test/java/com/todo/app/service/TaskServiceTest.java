package com.todo.app.service;

import com.todo.app.Fixtures;
import com.todo.app.dto.TaskBulkStatusRequest;
import com.todo.app.dto.TaskPatchRequest;
import com.todo.app.dto.TaskQuery;
import com.todo.app.dto.TaskUpsertRequest;
import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.entity.Task;
import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.TaskMapper;
import com.todo.app.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.todo.app.Fixtures.OWNER;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ReferenceResolver references;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(taskRepository, references, new TaskMapper());
    }

    @Test
    void createStoresTrimmedFieldsAndResolvesReferences() {
        Project project = Fixtures.project(3L, OWNER, "University");
        Tag tag = Fixtures.tag(9L, OWNER, "homework");
        when(references.resolveProject(OWNER, 3L)).thenReturn(project);
        when(references.resolveTags(OWNER, List.of("homework"))).thenReturn(Set.of(tag));

        var view = service.create(OWNER, new TaskUpsertRequest("  Write the report  ", "   ",
                TaskStatus.IN_PROGRESS, TaskPriority.HIGH, Fixtures.NOW, 3L, List.of("homework"), null));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Write the report");
        assertThat(saved.getValue().getDescription()).isNull();
        assertThat(saved.getValue().getProject()).isSameAs(project);
        assertThat(saved.getValue().getTags()).containsExactly(tag);
        assertThat(saved.getValue().getOwnerId()).isEqualTo(OWNER);
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(view.priority()).isEqualTo("HIGH");
        assertThat(view.projectId()).isEqualTo(3L);
    }

    @Test
    void creatingAnAlreadyDoneTaskRecordsWhenItFinished() {
        service.create(OWNER, new TaskUpsertRequest("Done already", null, TaskStatus.DONE,
                TaskPriority.LOW, null, null, null, null));

        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void findReportsMissingTaskAsNotFound() {
        when(taskRepository.findVisibleById(42L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(OWNER, 42L))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.NOT_FOUND);
                });
    }

    @Test
    void searchPassesLowercasedLikePatternAndWhitelistedSort() {
        when(taskRepository.findVisibleWithProject(eq(OWNER), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 0));

        service.search(OWNER, new TaskQuery(TaskStatus.TODO, null, null, null, null, " Report ", 2, 20, "password", false));

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findVisibleWithProject(eq(OWNER), eq(TaskStatus.TODO), eq(null), eq(null), eq(null),
                eq(null), pattern.capture(), any(), pageable.capture());
        assertThat(pattern.getValue()).isEqualTo("% report %");
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        // "password" is not sortable, so the default ordering on due date is used instead.
        assertThat(pageable.getValue().getSort().getOrderFor("dueAt")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("password")).isNull();
    }

    @Test
    void searchClampsPageSizeToTheAllowedMaximum() {
        when(taskRepository.findVisibleWithProject(anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        service.search(OWNER, new TaskQuery(null, null, null, null, null, null, 0, 5000, "createdAt", true));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findVisibleWithProject(anyLong(), any(), any(), any(), any(), any(), any(), any(),
                pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void searchMarksAPastDueOpenTaskAsOverdueInTheView() {
        Task late = Fixtures.openTask(1L, "Late thing");
        late.setDueAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(taskRepository.findVisibleWithProject(anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(late), PageRequest.of(0, 20), 1));

        var page = service.search(OWNER, new TaskQuery(null, null, null, null, null, null, 0, 20, "dueAt", false));

        assertThat(page.content()).singleElement().satisfies(view -> assertThat(view.overdue()).isTrue());
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void updateWithTheVersionTheClientReadRewritesTheTask() {
        Task task = Fixtures.openTask(5L, "Rename me");
        Fixtures.applyVersion(task, 4L);
        when(taskRepository.findVisibleById(5L, OWNER)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        service.update(OWNER, 5L, new TaskUpsertRequest("Renamed", "body", TaskStatus.IN_PROGRESS,
                TaskPriority.URGENT, null, null, List.of(), 4L));

        assertThat(task.getVersion()).isEqualTo(4L);
        assertThat(task.getTitle()).isEqualTo("Renamed");
        assertThat(task.getDescription()).isEqualTo("body");
        assertThat(task.getTags()).isEmpty();
    }

    @Test
    void updateWithAVersionFromAnOlderReadIsRefused() {
        Task task = Fixtures.openTask(5L, "Rename me");
        Fixtures.applyVersion(task, 4L);
        when(taskRepository.findVisibleById(5L, OWNER)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.update(OWNER, 5L, new TaskUpsertRequest("Stale form", null,
                TaskStatus.TODO, TaskPriority.LOW, null, null, null, 3L)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
                });

        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateTurnsAnOptimisticLockFailureIntoAConflictResponse() {
        Task task = Fixtures.openTask(5L, "Contended");
        when(taskRepository.findVisibleById(5L, OWNER)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenThrow(new ObjectOptimisticLockingFailureException(Task.class, 5L));

        assertThatThrownBy(() -> service.update(OWNER, 5L, new TaskUpsertRequest("New", null,
                TaskStatus.TODO, TaskPriority.LOW, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
                });
    }

    @Test
    void patchLeavesFieldsThatTheRequestDoesNotMention() {
        Task task = Fixtures.task(6L, "Keep", TaskStatus.TODO, TaskPriority.HIGH,
                Fixtures.NOW.plus(4, ChronoUnit.DAYS));
        Project project = Fixtures.project(2L, OWNER, "Home");
        task.setProject(project);
        when(taskRepository.findVisibleById(6L, OWNER)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        service.patch(OWNER, 6L, new TaskPatchRequest(null, null, TaskStatus.DONE, null, null, null, null, null, null));

        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getTitle()).isEqualTo("Keep");
        assertThat(task.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(task.getDueAt()).isNotNull();
        assertThat(task.getProject()).isSameAs(project);
        verify(references, never()).resolveProject(anyLong(), any());
    }

    @Test
    void patchCanClearTheDueDateExplicitly() {
        Task task = Fixtures.openTask(6L, "No longer scheduled");
        when(taskRepository.findVisibleById(6L, OWNER)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        service.patch(OWNER, 6L, new TaskPatchRequest(null, null, null, null, true, null, null, null, null));

        assertThat(task.getDueAt()).isNull();
    }

    @Test
    void patchReopensATaskByDroppingItsCompletionStamp() {
        Task task = Fixtures.task(6L, "Finished", TaskStatus.DONE, TaskPriority.MEDIUM, null);
        task.setCompletedAt(Fixtures.NOW);
        when(taskRepository.findVisibleById(6L, OWNER)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        service.patch(OWNER, 6L, new TaskPatchRequest(null, null, TaskStatus.IN_PROGRESS, null, null, null, null, null, null));

        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void bulkStatusUpdatesEverySelectedTask() {
        Task first = Fixtures.openTask(1L, "One");
        Task second = Fixtures.openTask(2L, "Two");
        when(taskRepository.findByIdInAndOwnerIdAndDeletedFalse(List.of(1L, 2L), OWNER)).thenReturn(List.of(first, second));

        var views = service.bulkStatus(OWNER, new TaskBulkStatusRequest(List.of(1L, 2L), TaskStatus.DONE));

        verify(taskRepository).saveAll(List.of(first, second));
        assertThat(views).hasSize(2);
        assertThat(first.getCompletedAt()).isNotNull();
        assertThat(second.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void bulkStatusFailsWhenAnyIdIsNotVisibleToTheCaller() {
        when(taskRepository.findByIdInAndOwnerIdAndDeletedFalse(List.of(1L, 2L), OWNER))
                .thenReturn(List.of(Fixtures.openTask(1L, "Mine")));

        assertThatThrownBy(() -> service.bulkStatus(OWNER, new TaskBulkStatusRequest(List.of(1L, 2L), TaskStatus.DONE)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(failure.getMessage()).contains("1 of the listed task ids");
                });
        verify(taskRepository, never()).saveAll(any());
    }

    @Test
    void deleteFlagsTheRowInsteadOfRemovingIt() {
        Task task = Fixtures.openTask(8L, "Drop me");
        when(taskRepository.findVisibleById(8L, OWNER)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        service.delete(OWNER, 8L);

        assertThat(task.isDeleted()).isTrue();
        verify(taskRepository, never()).delete(any());
    }

    @Test
    void statsAddsUpTheGroupedByStatusRows() {
        when(taskRepository.countByStatus(OWNER)).thenReturn(List.of(
                new Object[]{TaskStatus.TODO, 4L},
                new Object[]{TaskStatus.IN_PROGRESS, 2L},
                new Object[]{TaskStatus.DONE, 11L}));
        when(taskRepository.countOverdue(eq(OWNER), any())).thenReturn(3L);

        var stats = service.stats(OWNER);

        assertThat(stats.total()).isEqualTo(17);
        assertThat(stats.todo()).isEqualTo(4);
        assertThat(stats.inProgress()).isEqualTo(2);
        assertThat(stats.done()).isEqualTo(11);
        assertThat(stats.overdue()).isEqualTo(3);
    }

    @Test
    void statsHandlesAnAccountWithNoTasksAtAll() {
        when(taskRepository.countByStatus(OWNER)).thenReturn(List.of());
        when(taskRepository.countOverdue(eq(OWNER), any())).thenReturn(0L);

        assertThat(service.stats(OWNER).total()).isZero();
    }
}
