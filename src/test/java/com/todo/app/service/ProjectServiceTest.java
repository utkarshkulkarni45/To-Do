package com.todo.app.service;

import com.todo.app.Fixtures;
import com.todo.app.dto.ProjectRequest;
import com.todo.app.entity.Project;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.ProjectRepository;
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

import java.util.List;
import java.util.Optional;

import static com.todo.app.Fixtures.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private TaskRepository taskRepository;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, taskRepository, new ReferenceMapper());
    }

    @Test
    void listPaginatesInTheDatabaseAndCountsOpenTasksInOneQuery() {
        Project first = Fixtures.project(1L, OWNER, "University");
        Project second = Fixtures.project(2L, OWNER, "Home");
        when(projectRepository.findByOwnerIdAndDeletedFalse(eq(OWNER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));
        when(projectRepository.countOpenTasksForProjects(eq(OWNER), anyCollection()))
                .thenReturn(Fixtures.rows(new Object[]{1L, 5L}));

        var page = service.list(OWNER, 0, 20, "name");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(projectRepository).findByOwnerIdAndDeletedFalse(eq(OWNER), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).openTaskCount()).isEqualTo(5);
        assertThat(page.content().get(1).openTaskCount()).isZero();
        verify(taskRepository, never()).countOpenTasksInProject(anyLong(), anyLong());
    }

    @Test
    void listFallsBackToLastChangeWhenTheSortColumnIsNotSortable() {
        when(projectRepository.findByOwnerIdAndDeletedFalse(eq(OWNER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.list(OWNER, 0, 20, "password");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(projectRepository).findByOwnerIdAndDeletedFalse(eq(OWNER), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("password")).isNull();
        assertThat(pageable.getValue().getSort().getOrderFor("updatedAt")).isNotNull();
    }

    @Test
    void listSkipsTheCountQueryForAnEmptyPage() {
        when(projectRepository.findByOwnerIdAndDeletedFalse(eq(OWNER), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service.list(OWNER, 0, 20, "name").content()).isEmpty();
        verify(projectRepository, never()).countOpenTasksForProjects(anyLong(), anyCollection());
    }

    @Test
    void createRefusesADuplicateNameForTheSameAccount() {
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseAndDeletedFalse(OWNER, "Home")).thenReturn(true);

        assertThatThrownBy(() -> service.create(OWNER, new ProjectRequest("Home", null, "#2F6F53", null)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.CONFLICT);
                });
    }

    @Test
    void createStoresTheTrimmedNameAndNullsAnEmptyDescription() {
        when(projectRepository.existsByOwnerIdAndNameIgnoreCaseAndDeletedFalse(OWNER, "Home")).thenReturn(false);
        when(projectRepository.saveAndFlush(any(Project.class))).thenAnswer(call -> {
            Project project = call.getArgument(0);
            Fixtures.applyId(project, 12L);
            return project;
        });

        var view = service.create(OWNER, new ProjectRequest("  Home  ", "   ", "#B0562F", null));

        ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Home");
        assertThat(saved.getValue().getDescription()).isNull();
        assertThat(view.id()).isEqualTo(12L);
        assertThat(view.color()).isEqualTo("#B0562F");
    }

    @Test
    void renameIsAllowedToKeepItsOwnNameCaseInsensitively() {
        Project project = Fixtures.project(4L, OWNER, "Home");
        Fixtures.applyVersion(project, 2L);
        when(projectRepository.findByIdAndOwnerIdAndDeletedFalse(4L, OWNER)).thenReturn(Optional.of(project));
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        service.update(OWNER, 4L, new ProjectRequest(" home ", "note", "#FFFFFF", 2L));

        assertThat(project.getName()).isEqualTo("home");
        verify(projectRepository, never()).existsByOwnerIdAndNameIgnoreCaseAndDeletedFalse(anyLong(), any());
    }

    @Test
    void deleteWithOpenTasksNeedsTheForceFlag() {
        Project project = Fixtures.project(4L, OWNER, "Home");
        when(projectRepository.findByIdAndOwnerIdAndDeletedFalse(4L, OWNER)).thenReturn(Optional.of(project));
        when(taskRepository.countOpenTasksInProject(OWNER, 4L)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(OWNER, 4L, false))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getMessage()).contains("3 open tasks");
                });
        assertThat(project.isDeleted()).isFalse();
    }

    @Test
    void forcedDeleteFlagsTheProjectAndDetachesItsTasksInOneStatement() {
        Project project = Fixtures.project(4L, OWNER, "Home");
        when(projectRepository.findByIdAndOwnerIdAndDeletedFalse(4L, OWNER)).thenReturn(Optional.of(project));
        when(taskRepository.countOpenTasksInProject(OWNER, 4L)).thenReturn(3L);
        when(projectRepository.saveAndFlush(project)).thenReturn(project);

        service.delete(OWNER, 4L, true);

        assertThat(project.isDeleted()).isTrue();
        verify(taskRepository).detachFromProject(4L);
        verify(taskRepository, never()).delete(any());
    }

    @Test
    void deleteReportsAnUnknownProjectAsNotFound() {
        when(projectRepository.findByIdAndOwnerIdAndDeletedFalse(9L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(OWNER, 9L, true))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
