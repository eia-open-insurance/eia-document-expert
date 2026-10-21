package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when a document exists for the tenant but has no stored content.
 * Missing content is distinguishable from a missing document: the former is
 * {@code 404 CONTENT_NOT_FOUND}, the latter {@code 404 NOT_FOUND}.
 */
public class ContentNotFoundException extends RuntimeException {
    public ContentNotFoundException(UUID documentId) {
        super("Document content not found: " + documentId);
    }
}
