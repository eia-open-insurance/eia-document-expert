package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal row read of an immutable {@code document_content_version} revision.
 * {@link #version()} is both the revision number and the aggregate version of
 * the {@code document.content.uploaded} fact written with it.
 */
record DocumentContentVersion(
        UUID id,
        UUID documentId,
        UUID tenantId,
        int version,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        byte[] content,
        Instant uploadedAt
) {
    DocumentContentVersionMetadata metadata() {
        return new DocumentContentVersionMetadata(
                documentId, version, fileName, contentType, sizeBytes, sha256, uploadedAt);
    }
}
