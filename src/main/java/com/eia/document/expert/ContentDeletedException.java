package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when a document's content has been tombstoned and a caller tries to
 * download it. Treated as {@code 410 CONTENT_DELETED}, which is distinct from
 * content that never existed ({@code 404 CONTENT_NOT_FOUND}) and from a
 * document outside the tenant ({@code 404 NOT_FOUND}).
 */
public class ContentDeletedException extends RuntimeException {
    public ContentDeletedException(UUID documentId) {
        super("Document content has been deleted: " + documentId);
    }
}
