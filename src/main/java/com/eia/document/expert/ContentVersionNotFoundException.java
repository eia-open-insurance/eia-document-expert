package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when a document exists for the tenant but the requested content
 * revision does not. It is {@code 404 CONTENT_VERSION_NOT_FOUND}, which keeps a
 * missing revision distinguishable from a document outside the tenant
 * ({@code 404 NOT_FOUND}).
 */
public class ContentVersionNotFoundException extends RuntimeException {
    public ContentVersionNotFoundException(UUID documentId, int version) {
        super("Document content version " + version + " not found: " + documentId);
    }
}
