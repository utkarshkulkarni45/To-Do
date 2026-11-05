package com.todo.app.security;

public record AuthUser(long id, String email, String role) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
