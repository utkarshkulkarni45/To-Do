package com.todo.app.service;

import com.todo.app.Fixtures;
import com.todo.app.dto.UpdateUserRoleRequest;
import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import com.todo.app.enums.TaskStatus;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.TaskRepository;
import com.todo.app.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TaskRepository taskRepository;

    private AdminService service;

    @BeforeEach
    void setUp() {
        service = new AdminService(userRepository, taskRepository, new ReferenceMapper());
    }

    @Test
    void listUsersIsPagedAndSortedByAddress() {
        when(userRepository.findByDeletedFalse(any(Pageable.class))).thenAnswer(call ->
                new PageImpl<>(List.of(Fixtures.user(1L, "a@todo.local", Role.USER)), call.getArgument(0), 1));

        var page = service.listUsers(0, 500);

        assertThat(page.size()).isEqualTo(100);
        assertThat(page.content()).singleElement().satisfies(view -> assertThat(view.email()).isEqualTo("a@todo.local"));
    }

    @Test
    void anAdminCannotChangeTheirOwnRole() {
        assertThatThrownBy(() -> service.changeRole(5L, 5L, new UpdateUserRoleRequest(Role.USER)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.CONFLICT);
                });
        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void changeRoleAppliesTheRequestedRole() {
        User target = Fixtures.user(6L, "who@todo.local", Role.USER);
        when(userRepository.findByIdAndDeletedFalse(6L)).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);

        var view = service.changeRole(5L, 6L, new UpdateUserRoleRequest(Role.ADMIN));

        assertThat(target.getRole()).isEqualTo(Role.ADMIN);
        assertThat(view.role()).isEqualTo("ADMIN");
    }

    @Test
    void disablingSelfIsRefused() {
        assertThatThrownBy(() -> service.disable(5L, 5L))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void disableSoftDeletesTheAccount() {
        User target = Fixtures.user(6L, "who@todo.local", Role.USER);
        when(userRepository.findByIdAndDeletedFalse(6L)).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);
        when(taskRepository.countByStatus(6L)).thenReturn(Fixtures.rows(new Object[]{TaskStatus.TODO, 2L}));

        service.disable(5L, 6L);

        assertThat(target.isDeleted()).isTrue();
    }

    @Test
    void unknownAccountIsNotFound() {
        when(userRepository.findByIdAndDeletedFalse(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.user(404L))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
