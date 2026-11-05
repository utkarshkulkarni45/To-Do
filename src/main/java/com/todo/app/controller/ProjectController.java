package com.todo.app.controller;

import com.todo.app.dto.PageResponse;
import com.todo.app.dto.ProjectRequest;
import com.todo.app.dto.ProjectView;
import com.todo.app.security.CurrentUser;
import com.todo.app.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Grouping for tasks; deleting a project leaves its tasks in place")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @Operation(summary = "List projects with the number of open tasks in each")
    public PageResponse<ProjectView> list(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size,
                                          @RequestParam(defaultValue = "updatedAt") String sortBy) {
        return projectService.list(CurrentUser.requireId(), page, size, sortBy);
    }

    @PostMapping
    @Operation(summary = "Create a project")
    public ResponseEntity<ProjectView> create(@Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename or re-colour a project")
    public ProjectView update(@PathVariable long id, @Valid @RequestBody ProjectRequest request) {
        return projectService.update(CurrentUser.requireId(), id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a project. Refuses unless force=true when open tasks are attached")
    public ResponseEntity<Void> delete(@PathVariable long id,
                                       @RequestParam(defaultValue = "false") boolean force) {
        projectService.delete(CurrentUser.requireId(), id, force);
        return ResponseEntity.noContent().build();
    }
}
