package com.todo.app.service;

import com.todo.app.Fixtures;
import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.exception.ApiException;
import com.todo.app.repository.ProjectRepository;
import com.todo.app.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static com.todo.app.Fixtures.OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferenceResolverTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private TagRepository tagRepository;

    private ReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ReferenceResolver(projectRepository, tagRepository);
    }

    @Test
    void aMissingProjectIdMeansNoProject() {
        assertThat(resolver.resolveProject(OWNER, null)).isNull();
        verify(projectRepository, never()).findByIdAndOwnerIdAndDeletedFalse(anyLong(), anyLong());
    }

    @Test
    void aProjectOfAnotherAccountIsReportedAsNotFoundNotForbidden() {
        when(projectRepository.findByIdAndOwnerIdAndDeletedFalse(99L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveProject(OWNER, 99L))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    // The response must not confirm that somebody else's project exists.
                    assertThat(failure.getMessage()).doesNotContain("belongs");
                });
    }

    @Test
    void absentTagsMeansLeaveTheExistingOnesAlone() {
        assertThat(resolver.resolveTags(OWNER, null)).isNull();
    }

    @Test
    void tagsAreMatchedCaseInsensitivelyAndReused() {
        Tag existing = Fixtures.tag(1L, OWNER, "Reading");
        when(tagRepository.findAllOwnedByLowerCaseNames(eq(OWNER), anyCollection())).thenReturn(List.of(existing));

        var tags = resolver.resolveTags(OWNER, List.of("reading", "READING", " reading "));

        assertThat(tags).containsExactly(existing);
        verify(tagRepository, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownTagNameIsCreatedForTheAccount() {
        when(tagRepository.findAllOwnedByLowerCaseNames(eq(OWNER), anyCollection())).thenReturn(List.of());
        when(tagRepository.saveAndFlush(any(Tag.class))).thenAnswer(call -> {
            Tag tag = call.getArgument(0);
            Fixtures.applyId(tag, 44L);
            return tag;
        });

        var tags = resolver.resolveTags(OWNER, List.of("deep-work"));

        assertThat(tags).singleElement().satisfies(tag -> {
            assertThat(tag.getName()).isEqualTo("deep-work");
            assertThat(tag.getOwnerId()).isEqualTo(OWNER);
            assertThat(tag.getId()).isEqualTo(44L);
        });
    }

    @Test
    void anEmptyTagListClearsTheTasksTags() {
        assertThat(resolver.resolveTags(OWNER, List.of("  ", ""))).isEmpty();
        verify(tagRepository, never()).findAllOwnedByLowerCaseNames(anyLong(), anyCollection());
    }

    @Test
    void projectLookupUsesTheOwnedFinder() {
        Project project = Fixtures.project(2L, OWNER, "Home");
        when(projectRepository.findByIdAndOwnerIdAndDeletedFalse(2L, OWNER)).thenReturn(Optional.of(project));

        assertThat(resolver.resolveProject(OWNER, 2L)).isSameAs(project);
    }
}
