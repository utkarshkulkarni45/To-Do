package com.todo.app.dto;

import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Every field is optional; absent means "unchanged". dueAtSet/projectIdSet distinguish "leave the due
 * date alone" from "clear the due date", which a plain null cannot express.
 */
public record TaskPatchRequest(
        @Size(min = 1, max = 200) String title,
        @Size(max = 2000) String description,
        TaskStatus status,
        TaskPriority priority,
        Boolean clearDueAt,
        Instant dueAt,
        Boolean clearProject,
        Long projectId,
        Long version) {

    public boolean dueAtSet() {
        return Boolean.TRUE.equals(clearDueAt) || dueAt != null;
    }

    public boolean projectIdSet() {
        return Boolean.TRUE.equals(clearProject) || projectId != null;
    }
}
