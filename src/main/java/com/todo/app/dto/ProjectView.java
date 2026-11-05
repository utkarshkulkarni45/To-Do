package com.todo.app.dto;

public record ProjectView(Long id, String name, String description, String color, long openTaskCount, long version) {
}
