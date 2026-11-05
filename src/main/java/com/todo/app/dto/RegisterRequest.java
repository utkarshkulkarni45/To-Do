package com.todo.app.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Size(min = 10, max = 72)
        @Pattern(regexp = ".*[A-Za-z].*", message = "must contain at least one letter")
        @Pattern(regexp = ".*[0-9].*", message = "must contain at least one digit")
        String password) {
}
