package com.todo.app.dto;

import java.time.Instant;
import java.util.List;

public record TaskView(
        Long id,
        String title,
        String description,
        String status,
        String priority,
        Instant dueAt,
        boolean overdue,
        Instant completedAt,
        Long projectId,
        String projectName,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
