package com.todo.app.service;

import com.todo.app.dto.TagRequest;
import com.todo.app.dto.TagView;
import com.todo.app.entity.Tag;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.TagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class TagService {

    private final TagRepository tagRepository;
    private final ReferenceMapper mapper;

    public TagService(TagRepository tagRepository, ReferenceMapper mapper) {
        this.tagRepository = tagRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<TagView> list(long ownerId) {
        return tagRepository.findByOwnerIdAndDeletedFalseOrderByNameAsc(ownerId).stream()
                .map(mapper::toView)
                .toList();
    }

    @Transactional
    public TagView create(long ownerId, TagRequest request) {
        String name = request.name().trim();
        tagRepository.findByOwnerIdAndNameIgnoreCaseAndDeletedFalse(ownerId, name)
                .ifPresent(existing -> {
                    throw ApiException.conflict(ErrorCodes.CONFLICT, "You already have a tag called " + name);
                });
        Tag tag = new Tag(ownerId, name);
        tagRepository.saveAndFlush(tag);
        return mapper.toView(tag);
    }

    @Transactional
    public TagView rename(long ownerId, long tagId, TagRequest request) {
        Tag tag = load(ownerId, tagId);
        String name = request.name().trim();
        if (!tag.getName().equalsIgnoreCase(name)) {
            tagRepository.findByOwnerIdAndNameIgnoreCaseAndDeletedFalse(ownerId, name)
                    .ifPresent(other -> {
                        throw ApiException.conflict(ErrorCodes.CONFLICT, "You already have a tag called " + name);
                    });
        }
        VersionedWrites.requireUnchanged("tag", tag.getVersion(), request.version());
        tag.setName(name);
        return mapper.toView(persist(tag));
    }

    @Transactional
    public void delete(long ownerId, long tagId) {
        Tag tag = load(ownerId, tagId);
        tag.setDeleted(true);
        persist(tag);
    }

    private Tag load(long ownerId, long tagId) {
        return tagRepository.findByIdAndOwnerIdAndDeletedFalse(tagId, ownerId)
                .orElseThrow(() -> ApiException.notFound("Tag", tagId));
    }

    private Tag persist(Tag tag) {
        try {
            return tagRepository.saveAndFlush(tag);
        } catch (ObjectOptimisticLockingFailureException conflict) {
            throw VersionedWrites.conflict("tag");
        }
    }
}
