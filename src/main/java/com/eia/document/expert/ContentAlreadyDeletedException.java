package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when a delete is repeated on content whose tombstone is already in
 * place. The first delete wins; repeating it is a structured
 * {@code 409 CONTENT_ALREADY_DELETED} rather than a silent success, so a caller
 * cannot mistake a stale delete for a fresh tombstone (and no second outbox
 * fact is written).
 */
public class ContentAlreadyDeletedException extends RuntimeException {
    public ContentAlreadyDeletedException(UUID documentId) {
        super("Document content is already deleted: " + documentId);
    }
}
