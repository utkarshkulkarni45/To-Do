package com.todo.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
        @NotBlank @Size(min = 1, max = 120) String name,
        @Size(max = 500) String description,
        @Pattern(regexp = "^#([0-9a-fA-F]{6})$", message = "must be a hex colour such as #1F7A5C") String color,
        Long version) {
}
