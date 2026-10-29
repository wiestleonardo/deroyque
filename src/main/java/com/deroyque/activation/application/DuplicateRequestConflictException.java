package com.deroyque.activation.application;

import java.util.UUID;

/** Mesmo externalId já usado por este consumidor, porém com conteúdo diferente. */
public class DuplicateRequestConflictException extends RuntimeException {

    private final String externalId;
    private final UUID existingId;

    public DuplicateRequestConflictException(String externalId, UUID existingId) {
        super("externalId " + externalId + " já foi usado com outro conteúdo");
        this.externalId = externalId;
        this.existingId = existingId;
    }

    public String getExternalId() {
        return externalId;
    }

    public UUID getExistingId() {
        return existingId;
    }
}
