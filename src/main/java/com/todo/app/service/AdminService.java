package com.todo.app.service;

import com.todo.app.dto.PageResponse;
import com.todo.app.dto.UpdateUserRoleRequest;
import com.todo.app.dto.UserView;
import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.TaskRepository;
import com.todo.app.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final ReferenceMapper mapper;

    public AdminService(UserRepository userRepository, TaskRepository taskRepository, ReferenceMapper mapper) {
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserView> listUsers(int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("email"));
        return PageResponse.from(userRepository.findByDeletedFalse(pageable).map(mapper::toView));
    }

    @Transactional(readOnly = true)
    public UserView user(long userId) {
        return mapper.toView(load(userId));
    }

    @Transactional
    public UserView changeRole(long actorId, long userId, UpdateUserRoleRequest request) {
        if (actorId == userId) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                    "You cannot change your own role; that would let an admin demote themselves out of the role");
        }
        User user = load(userId);
        user.setRole(request.role());
        log.info("Admin {} set role {} on user {}", actorId, request.role(), userId);
        return mapper.toView(userRepository.save(user));
    }

    /**
     * Disabling is a soft delete on the account. Login and the user-service lookup both filter deleted
     * accounts, so the next attempt fails; an already issued access token still works until it expires,
     * which is the cost of a stateless token. Short access-token lifetimes bound that window, and the
     * README lists a blocklist as the alternative if immediate revocation is required.
     */
    @Transactional
    public void disable(long actorId, long userId) {
        if (actorId == userId) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, "You cannot disable your own account");
        }
        User user = load(userId);
        long tasks = taskRepository.countByStatus(user.getId()).stream()
                .mapToLong(row -> ((Number) row[1]).longValue())
                .sum();
        user.setDeleted(true);
        userRepository.save(user);
        log.info("Admin {} disabled user {} holding {} tasks", actorId, userId, tasks);
    }

    private User load(long userId) {
        return userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> ApiException.notFound("Account", userId));
    }
}
