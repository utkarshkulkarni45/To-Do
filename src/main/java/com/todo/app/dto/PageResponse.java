package com.todo.app.dto;

import java.util.List;

/**
 * Envelope for paged results. Page metadata is exposed explicitly instead of dumping Spring's
 * PageImpl JSON, so the response shape does not change when the framework is upgraded.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(org.springframework.data.domain.Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast());
    }
}
