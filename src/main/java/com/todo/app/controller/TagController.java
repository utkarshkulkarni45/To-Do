package com.todo.app.controller;

import com.todo.app.dto.TagRequest;
import com.todo.app.dto.TagView;
import com.todo.app.security.CurrentUser;
import com.todo.app.service.TagService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
@Tag(name = "Tags", description = "Free-form labels, created automatically when a task names one")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    @Operation(summary = "List this account's tags in alphabetical order")
    public List<TagView> list() {
        return tagService.list(CurrentUser.requireId());
    }

    @PostMapping
    @Operation(summary = "Create a tag")
    public ResponseEntity<TagView> create(@Valid @RequestBody TagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tagService.create(CurrentUser.requireId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a tag")
    public TagView rename(@PathVariable long id, @Valid @RequestBody TagRequest request) {
        return tagService.rename(CurrentUser.requireId(), id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a tag; tasks keep working without it")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        tagService.delete(CurrentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }
}
