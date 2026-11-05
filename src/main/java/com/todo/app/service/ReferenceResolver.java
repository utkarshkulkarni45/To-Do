package com.todo.app.service;

import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.exception.ApiException;
import com.todo.app.repository.ProjectRepository;
import com.todo.app.repository.TagRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns the project id and tag names on a task request into entities the user is allowed to attach.
 * References that belong to somebody else are reported as not found rather than forbidden, so the API
 * does not confirm that another user's project exists.
 */
@Component
class ReferenceResolver {

    private final ProjectRepository projectRepository;
    private final TagRepository tagRepository;

    ReferenceResolver(ProjectRepository projectRepository, TagRepository tagRepository) {
        this.projectRepository = projectRepository;
        this.tagRepository = tagRepository;
    }

    Project resolveProject(long ownerId, Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectRepository.findByIdAndOwnerIdAndDeletedFalse(projectId, ownerId)
                .orElseThrow(() -> ApiException.notFound("Project", projectId));
    }

    Set<Tag> resolveTags(long ownerId, List<String> names) {
        if (names == null) {
            return null;
        }
        List<String> normalised = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim())
                .distinct()
                .toList();
        if (normalised.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Map<String, Tag> existing = tagRepository
                .findAllOwnedByLowerCaseNames(ownerId, normalised.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(tag -> tag.getName().toLowerCase(Locale.ROOT), Function.identity()));
        Set<Tag> resolved = new LinkedHashSet<>();
        for (String name : normalised) {
            Tag tag = existing.get(name.toLowerCase(Locale.ROOT));
            if (tag == null) {
                tag = tagRepository.saveAndFlush(new Tag(ownerId, name));
                existing.put(name.toLowerCase(Locale.ROOT), tag);
            }
            resolved.add(tag);
        }
        return resolved;
    }
}
