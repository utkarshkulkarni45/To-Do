package com.todo.app.dto;

import com.todo.app.enums.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateUserRoleRequest(@NotNull Role role) {
}
