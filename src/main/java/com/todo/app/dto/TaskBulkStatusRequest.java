package com.todo.app.dto;

import com.todo.app.enums.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TaskBulkStatusRequest(
        @NotNull @Size(min = 1, max = 200) List<@NotNull Long> ids,
        @NotNull TaskStatus status) {
}
