package com.todo.app.repository;

import com.todo.app.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByOwnerIdAndDeletedFalseOrderByNameAsc(long ownerId);

    Optional<Tag> findByIdAndOwnerIdAndDeletedFalse(long id, long ownerId);

    Optional<Tag> findByOwnerIdAndNameIgnoreCaseAndDeletedFalse(long ownerId, String name);

    @Query("select t from Tag t where t.ownerId = :ownerId and t.deleted = false and t.id in :ids")
    List<Tag> findAllOwned(@Param("ids") Collection<Long> ids, @Param("ownerId") long ownerId);

    @Query("select t from Tag t where t.ownerId = :ownerId and t.deleted = false and lower(t.name) in :names")
    List<Tag> findAllOwnedByLowerCaseNames(@Param("ownerId") long ownerId, @Param("names") Collection<String> names);
}
