package com.todo.app.controller;

import com.todo.app.dto.PageResponse;
import com.todo.app.dto.UpdateUserRoleRequest;
import com.todo.app.dto.UserView;
import com.todo.app.security.CurrentUser;
import com.todo.app.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reachable only by the ADMIN role; the rule lives in SecurityConfig so that it applies to the whole
 * path without needing per-method annotations.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Administration", description = "USER or ADMIN accounts and their roles")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    @Operation(summary = "Page through the accounts in alphabetical order")
    public PageResponse<UserView> users(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        return adminService.listUsers(page, size);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Read one account")
    public UserView user(@PathVariable long id) {
        return adminService.user(id);
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Promote or demote an account")
    public UserView changeRole(@PathVariable long id, @Valid @RequestBody UpdateUserRoleRequest request) {
        return adminService.changeRole(CurrentUser.requireId(), id, request);
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "Disable an account: no further sign-in, data kept")
    public ResponseEntity<Void> disable(@PathVariable long id) {
        adminService.disable(CurrentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }
}
