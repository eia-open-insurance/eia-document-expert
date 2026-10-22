package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Internal row read of a {@code document_content_version} revision.
 * {@link #version()} is both the revision number and the aggregate version of
 * the {@code document.content.uploaded} fact written with it.
 *
 * <p>{@code deletedAt} is non-null when the document's content was tombstoned;
 * the revision keeps its metadata but its bytes are cleared. A tombstoned
 * revision is not downloadable.</p>
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
        Instant uploadedAt,
        Instant deletedAt
) {
    DocumentContentVersionMetadata metadata() {
        return new DocumentContentVersionMetadata(
                documentId, version, fileName, contentType, sizeBytes, sha256, uploadedAt);
    }
}
