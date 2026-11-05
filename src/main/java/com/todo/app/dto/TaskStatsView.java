package com.todo.app.dto;

public record TaskStatsView(long total, long todo, long inProgress, long done, long overdue) {
}
