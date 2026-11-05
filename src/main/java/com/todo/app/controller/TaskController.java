package com.todo.app.controller;

import com.todo.app.dto.PageResponse;
import com.todo.app.dto.TaskBulkStatusRequest;
import com.todo.app.dto.TaskPatchRequest;
import com.todo.app.dto.TaskQuery;
import com.todo.app.dto.TaskStatsView;
import com.todo.app.dto.TaskUpsertRequest;
import com.todo.app.dto.TaskView;
import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;
import com.todo.app.security.CurrentUser;
import com.todo.app.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@Tag(name = "Tasks", description = "The user's own tasks; every read and write is scoped to the token's account")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "Page through tasks with optional filters, search and sorting")
    public PageResponse<TaskView> list(@RequestParam(required = false) TaskStatus status,
                                       @RequestParam(required = false) TaskPriority priority,
                                       @RequestParam(required = false) Long projectId,
                                       @RequestParam(required = false) Long tagId,
                                       @RequestParam(required = false) Boolean overdue,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(defaultValue = "dueAt") String sortBy,
                                       @RequestParam(defaultValue = "false") boolean descending) {
        long ownerId = CurrentUser.requireId();
        return taskService.search(ownerId, new TaskQuery(status, priority, projectId, tagId, overdue,
                blankToNull(q), page, size, sortBy, descending));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one task including its tags")
    public TaskView find(@PathVariable long id) {
        return taskService.find(CurrentUser.requireId(), id);
    }

    @PostMapping
    @Operation(summary = "Create a task; unknown tag names are created for this account")
    public ResponseEntity<TaskView> create(@Valid @RequestBody TaskUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a task. Send the version you read to prevent overwriting a newer copy")
    public TaskView update(@PathVariable long id, @Valid @RequestBody TaskUpsertRequest request) {
        return taskService.update(CurrentUser.requireId(), id, request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change single fields only, used by the list's status and priority controls")
    public TaskView patch(@PathVariable long id, @Valid @RequestBody TaskPatchRequest request) {
        return taskService.patch(CurrentUser.requireId(), id, request);
    }

    @PostMapping("/bulk-status")
    @Operation(summary = "Set the same status on up to 200 tasks in one call")
    public List<TaskView> bulkStatus(@Valid @RequestBody TaskBulkStatusRequest request) {
        return taskService.bulkStatus(CurrentUser.requireId(), request);
    }

    @GetMapping("/stats")
    @Operation(summary = "Totals per status plus the overdue count; cached for two minutes")
    public TaskStatsView stats() {
        return taskService.stats(CurrentUser.requireId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft delete: the task stops appearing everywhere but the row is kept")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        taskService.delete(CurrentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
