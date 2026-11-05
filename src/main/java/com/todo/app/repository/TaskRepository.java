package com.todo.app.repository;

import com.todo.app.entity.Task;
import com.todo.app.enums.TaskPriority;
import com.todo.app.enums.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * The list query a dashboard runs. `project` is fetched in the same select because it is always
     * needed for the view, and as a left join because a task with no project must still be listed;
     * `tags` is left lazy and loaded in one extra statement per page by the @BatchSize on the
     * collection, which avoids the N+1 select without breaking pagination the way two join fetches in
     * one paged query would.
     *
     * Overdue is not a column, so it is derived inside the query with a case expression: a task is
     * overdue when its due date has passed and it is not done. The case keeps the comparison boolean
     * even for a task with no due date, and HQL cannot compare a parenthesis expression to a parameter.
     * The keyword is already a lowercased like pattern by the time it reaches here, because lowering
     * the parameter in SQL leaves PostgreSQL unable to work out its type.
     */
    @Query("""
            select t from Task t
              left join fetch t.project p
            where t.ownerId = :ownerId and t.deleted = false
              and (:status is null or t.status = :status)
              and (:priority is null or t.priority = :priority)
              and (:projectId is null or t.project.id = :projectId)
              and (:tagId is null or exists (select 1 from t.tags g where g.id = :tagId))
              and (:overdue is null or case
                       when t.dueAt < :now and t.status <> com.todo.app.enums.TaskStatus.DONE
                       then true else false end = :overdue)
              and (:q is null or lower(t.title) like :q or lower(coalesce(t.description, '')) like :q)
            """)
    Page<Task> findVisibleWithProject(@Param("ownerId") long ownerId,
                                      @Param("status") TaskStatus status,
                                      @Param("priority") TaskPriority priority,
                                      @Param("projectId") Long projectId,
                                      @Param("tagId") Long tagId,
                                      @Param("overdue") Boolean overdue,
                                      @Param("q") String keywordPattern,
                                      @Param("now") Instant now,
                                      Pageable pageable);

    @Query("""
            select t from Task t
              left join fetch t.tags
              left join fetch t.project
            where t.id = :id and t.ownerId = :ownerId and t.deleted = false
            """)
    Optional<Task> findVisibleById(@Param("id") long id, @Param("ownerId") long ownerId);

    boolean existsByIdAndOwnerIdAndDeletedFalse(long id, long ownerId);

    List<Task> findByIdInAndOwnerIdAndDeletedFalse(Collection<Long> ids, long ownerId);

    interface ReminderRow {

        Long getId();

        String getTitle();

        Instant getDueAt();

        String getOwnerEmail();

        String getOwnerName();
    }

    /**
     * One statement for the reminder job, joining the owner so that sending mail does not trigger a
     * user lookup per task.
     */
    @Query("""
            select t.id as id, t.title as title, t.dueAt as dueAt, u.email as ownerEmail, u.fullName as ownerName
            from Task t join User u on u.id = t.ownerId
            where t.deleted = false and t.status <> com.todo.app.enums.TaskStatus.DONE
              and t.dueAt between :dueFrom and :dueTo and t.reminderSentAt is null
            order by t.dueAt
            """)
    List<ReminderRow> findReminderRows(@Param("dueFrom") Instant from, @Param("dueTo") Instant to, Pageable limit);

    /**
     * Flags the reminded tasks in bulk. Doing this after sending means a crash mid-run can resend one
     * batch of reminders, which is the acceptable direction of failure for a courtesy email.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Task t set t.reminderSentAt = :now, t.updatedAt = :now
            where t.id in :ids and t.reminderSentAt is null
            """)
    int markRemindersSent(@Param("ids") List<Long> ids, @Param("now") Instant now);

    @Query("""
            select t.status, count(t) from Task t
            where t.ownerId = :ownerId and t.deleted = false
            group by t.status
            """)
    List<Object[]> countByStatus(@Param("ownerId") long ownerId);

    @Query("""
            select count(t) from Task t
            where t.ownerId = :ownerId and t.deleted = false
              and t.status <> com.todo.app.enums.TaskStatus.DONE and t.dueAt < :now
            """)
    long countOverdue(@Param("ownerId") long ownerId, @Param("now") Instant now);

    @Query("""
            select count(t) from Task t
            where t.ownerId = :ownerId and t.deleted = false and t.project.id = :projectId
              and t.status <> com.todo.app.enums.TaskStatus.DONE
            """)
    long countOpenTasksInProject(@Param("ownerId") long ownerId, @Param("projectId") long projectId);

    /**
     * Detach every task from a project that is being removed. A bulk statement rather than loading
     * each task, which is what a project with thousands of tasks needs. It bypasses the optimistic
     * locking version, acceptable here because only the project link changes.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Task t set t.project = null where t.project.id = :projectId")
    int detachFromProject(@Param("projectId") long projectId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Task t where t.deleted = true and t.updatedAt < :before")
    int purgeSoftDeleted(@Param("before") Instant before);
}
