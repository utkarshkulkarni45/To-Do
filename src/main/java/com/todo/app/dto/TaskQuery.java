package com.todo.app.dto;

import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;

/**
 * The filter/sort/paging options for a task search, already normalised by the controller.
 */
public record TaskQuery(TaskStatus status,
                        TaskPriority priority,
                        Long projectId,
                        Long tagId,
                        Boolean overdue,
                        String keyword,
                        int page,
                        int size,
                        String sortProperty,
                        boolean sortDescending) {
}
