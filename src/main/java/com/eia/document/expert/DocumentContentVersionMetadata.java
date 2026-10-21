package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Public projection of one stored content revision.
 *
 * <p>{@code documentId} is repeated from the path so a history response is
 * self-describing when a caller concatenates the lists of several documents.
 * The bytes are intentionally absent: callers that need a revision download it
 * from {@code GET .../content/versions/{version}}, which exposes the same
 * digest in {@code X-Content-SHA256}.</p>
 */
public record DocumentContentVersionMetadata(
        UUID documentId,
        int version,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt
) {}
