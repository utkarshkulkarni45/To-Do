package com.todo.app.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tags")
public class Tag extends BaseUserOwnedEntity {

    @Column(name = "name", nullable = false)
    private String name;

    protected Tag() {
    }

    public Tag(long ownerId, String name) {
        setOwnerId(ownerId);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
