package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when a document does not exist for the supplied tenant. The expert
 * never leaks whether a row exists in another tenant.
 */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(UUID id) {
        super("Document not found: " + id);
    }
}
