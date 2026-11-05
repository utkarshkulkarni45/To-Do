package com.todo.app.service;

import com.todo.app.Fixtures;
import com.todo.app.dto.TagRequest;
import com.todo.app.entity.Tag;
import com.todo.app.exception.ApiException;
import com.todo.app.exception.ErrorCodes;
import com.todo.app.mapper.ReferenceMapper;
import com.todo.app.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static com.todo.app.Fixtures.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagRepository tagRepository;

    private TagService service;

    @BeforeEach
    void setUp() {
        service = new TagService(tagRepository, new ReferenceMapper());
    }

    @Test
    void listReturnsTheAccountsOwnTagsInOrder() {
        when(tagRepository.findByOwnerIdAndDeletedFalseOrderByNameAsc(OWNER))
                .thenReturn(List.of(Fixtures.tag(1L, OWNER, "reading"), Fixtures.tag(2L, OWNER, "errand")));

        assertThat(service.list(OWNER)).extracting("name").containsExactly("reading", "errand");
    }

    @Test
    void createRefusesANameThatDiffersOnlyInCase() {
        when(tagRepository.findByOwnerIdAndNameIgnoreCaseAndDeletedFalse(OWNER, "Reading"))
                .thenReturn(Optional.of(Fixtures.tag(1L, OWNER, "reading")));

        assertThatThrownBy(() -> service.create(OWNER, new TagRequest(" Reading ", null)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.CONFLICT);
                });
    }

    @Test
    void createStoresTheTrimmedName() {
        when(tagRepository.findByOwnerIdAndNameIgnoreCaseAndDeletedFalse(OWNER, "deep-work")).thenReturn(Optional.empty());
        when(tagRepository.saveAndFlush(any(Tag.class))).thenAnswer(call -> {
            Tag tag = call.getArgument(0);
            Fixtures.applyId(tag, 8L);
            return tag;
        });

        var view = service.create(OWNER, new TagRequest("  deep-work ", null));

        ArgumentCaptor<Tag> saved = ArgumentCaptor.forClass(Tag.class);
        verify(tagRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("deep-work");
        assertThat(view.id()).isEqualTo(8L);
    }

    @Test
    void renameRejectsAVersionTheCallerNeverRead() {
        Tag tag = Fixtures.tag(3L, OWNER, "old");
        Fixtures.applyVersion(tag, 7L);
        when(tagRepository.findByIdAndOwnerIdAndDeletedFalse(3L, OWNER)).thenReturn(Optional.of(tag));

        assertThatThrownBy(() -> service.rename(OWNER, 3L, new TagRequest("new", 6L)))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getCode()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
                });

        verify(tagRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteIsASoftDelete() {
        Tag tag = Fixtures.tag(3L, OWNER, "drop");
        when(tagRepository.findByIdAndOwnerIdAndDeletedFalse(3L, OWNER)).thenReturn(Optional.of(tag));
        when(tagRepository.saveAndFlush(tag)).thenReturn(tag);

        service.delete(OWNER, 3L);

        assertThat(tag.isDeleted()).isTrue();
    }

    @Test
    void unknownTagIdIsNotFound() {
        when(tagRepository.findByIdAndOwnerIdAndDeletedFalse(70L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(OWNER, 70L))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
