package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * Public projection of the current content stored for a document.
 *
 * <p>{@code currentVersion} names the revision returned by the download route
 * and listed by the version endpoints. The bytes are intentionally absent:
 * callers that need them use the download route, which streams the stored
 * content and exposes the same digest in {@code X-Content-SHA256}.</p>
 */
public record DocumentContentMetadata(
        UUID documentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        Instant uploadedAt,
        int currentVersion
) {}
