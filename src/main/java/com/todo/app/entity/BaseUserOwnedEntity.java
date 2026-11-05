package com.todo.app.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseUserOwnedEntity extends AuditableByUserEntity {

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    public long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(long ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
