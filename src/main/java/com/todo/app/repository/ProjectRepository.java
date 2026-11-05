package com.todo.app.repository;

import com.todo.app.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerIdAndDeletedFalseOrderByUpdatedAtDesc(long ownerId);

    Optional<Project> findByIdAndOwnerIdAndDeletedFalse(long id, long ownerId);

    boolean existsByOwnerIdAndNameIgnoreCaseAndDeletedFalse(long ownerId, String name);

    @Query("select p from Project p where p.ownerId = :ownerId and p.deleted = false and p.id in :ids")
    List<Project> findAllOwned(@Param("ids") Collection<Long> ids, @Param("ownerId") long ownerId);

    Page<Project> findByOwnerIdAndDeletedFalse(long ownerId, Pageable pageable);

    /**
     * Open task counts for a whole page of projects in one grouped statement, instead of one count
     * query per project.
     */
    @Query("""
            select t.project.id, count(t) from Task t
            where t.ownerId = :ownerId and t.deleted = false
              and t.status <> com.todo.app.enums.TaskStatus.DONE and t.project.id in :projectIds
            group by t.project.id
            """)
    List<Object[]> countOpenTasksForProjects(@Param("ownerId") long ownerId, @Param("projectIds") Collection<Long> projectIds);
}
