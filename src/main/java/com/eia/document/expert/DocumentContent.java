package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal row read of the {@code document_content} current pointer.
 * {@link #currentVersion()} names the revision in
 * {@code document_content_version} whose metadata is copied here for the
 * current read and download routes.
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
        int currentVersion
) {
    DocumentContentMetadata metadata() {
        return new DocumentContentMetadata(
                documentId, fileName, contentType, sizeBytes, sha256, uploadedAt,
                currentVersion);
    }
}
