package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal row read of {@code document_content}. {@link #version()} is the
 * content revision used as the aggregate version of
 * {@code document.content.uploaded}; it increments on every replacement.
 */
record DocumentContent(
        UUID documentId,
        UUID tenantId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        byte[] content,
        Instant uploadedAt,
        long version
) {
    DocumentContentMetadata metadata() {
        return new DocumentContentMetadata(
                documentId, fileName, contentType, sizeBytes, sha256, uploadedAt);
    }
}
