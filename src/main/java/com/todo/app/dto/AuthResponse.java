package com.todo.app.dto;

public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds, UserView user) {
}
