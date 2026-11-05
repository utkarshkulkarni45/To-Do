package com.todo.app.repository;

import com.todo.app.entity.User;
import com.todo.app.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailAndDeletedFalse(String email);

    boolean existsByEmail(String email);

    Optional<User> findByIdAndDeletedFalse(long id);

    Page<User> findByDeletedFalse(Pageable pageable);

    List<User> findByRoleAndDeletedFalse(Role role);
}
