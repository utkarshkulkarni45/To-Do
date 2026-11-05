package com.todo.app.mapper;

import com.todo.app.dto.TaskView;
import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.entity.Task;
import com.todo.app.enums.TaskStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class TaskMapper {

    public TaskView toView(Task task, Instant now) {
        Project project = task.getProject();
        return new TaskView(
                task.getId(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus().name(),
                task.getPriority().name(),
                task.getDueAt(),
                isOverdue(task, now),
                task.getCompletedAt(),
                project == null ? null : project.getId(),
                project == null ? null : project.getName(),
                tagNames(task),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getVersion());
    }

    public List<String> tagNames(Task task) {
        return task.getTags().stream().map(Tag::getName).sorted().toList();
    }

    /**
     * Derived rather than stored: a task becomes overdue on its own as time passes, so the answer has
     * to be computed against the current clock. The scheduled job exists only to keep the "overdue"
     * index and reminder logic fresh.
     */
    public boolean isOverdue(Task task, Instant now) {
        return task.getDueAt() != null && task.getStatus() != TaskStatus.DONE && task.getDueAt().isBefore(now);
    }
}
