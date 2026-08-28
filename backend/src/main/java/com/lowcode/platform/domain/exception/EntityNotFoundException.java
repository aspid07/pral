package com.lowcode.platform.domain.exception;

import java.util.UUID;

public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String entityName, UUID id) {
        super(entityName + " not found: " + id);
    }
}
