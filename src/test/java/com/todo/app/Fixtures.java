package com.todo.app;

import com.todo.app.entity.Project;
import com.todo.app.entity.Tag;
import com.todo.app.entity.Task;
import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class Fixtures {

    public static final long OWNER = 7L;
    public static final Instant NOW = Instant.parse("2026-11-04T09:00:00Z");

    private Fixtures() {
    }

    public static Task task(long id, String title, TaskStatus status, TaskPriority priority, Instant dueAt) {
        Task task = new Task(OWNER, title);
        setId(task, id);
        task.setStatus(status);
        task.setPriority(priority);
        task.setDueAt(dueAt);
        return task;
    }

    public static Task openTask(long id, String title) {
        return task(id, title, TaskStatus.TODO, TaskPriority.MEDIUM, NOW.plus(1, ChronoUnit.DAYS));
    }

    public static Project project(long id, long ownerId, String name) {
        Project project = new Project(ownerId, name, null, "#2F6F53");
        setId(project, id);
        return project;
    }

    public static Tag tag(long id, long ownerId, String name) {
        Tag tag = new Tag(ownerId, name);
        setId(tag, id);
        return tag;
    }

    public static User user(long id, String email, Role role) {
        User user = new User(email, "Test User", "hashed", role);
        setId(user, id);
        return user;
    }

    public static List<Task> tasks(Task... items) {
        return new ArrayList<>(List.of(items));
    }

    /** Aggregate queries return tuples, which a bare List.of would flatten. */
    public static List<Object[]> rows(Object[]... tuples) {
        return List.of(tuples);
    }

    /** Entities normally receive their id and version from the database; unit tests need them too. */
    public static void applyId(com.todo.app.entity.BaseEntity entity, long id) {
        set(entity, "id", id);
    }

    public static void applyVersion(com.todo.app.entity.BaseEntity entity, long version) {
        set(entity, "version", version);
    }

    private static void set(com.todo.app.entity.BaseEntity entity, String field, Object value) {
        try {
            java.lang.reflect.Field declared = com.todo.app.entity.BaseEntity.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(entity, value);
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("Fixtures cannot assign " + field, missing);
        }
    }

    private static void setId(com.todo.app.entity.BaseEntity entity, long id) {
        applyId(entity, id);
    }
}
