package com.todo.app.dto;

import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record TaskUpsertRequest(
        @NotNull @Size(min = 1, max = 200) String title,
        @Size(max = 2000) String description,
        @NotNull TaskStatus status,
        @NotNull TaskPriority priority,
        Instant dueAt,
        Long projectId,
        @Size(max = 20) List<@NotNull @Size(min = 1, max = 60) String> tags,
        Long version) {
}
