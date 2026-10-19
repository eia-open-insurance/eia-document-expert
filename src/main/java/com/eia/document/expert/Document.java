package com.eia.document.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The document aggregate root for the first vertical slice.
 *
 * <p>Field names follow SPEC section 11.6.4. Content, renditions, object
 * storage keys, and hashes are deliberately out of this slice; the document
 * table carries the metadata that the lifecycle and retention invariants
 * operate on.</p>
 */
public record Document(
        UUID id,
        UUID tenantId,
        String documentNumber,
        String title,
        String documentType,
        String classification,
        String status,
        LocalDate retentionUntil,
        int version,
        Instant createdAt,
        Instant updatedAt
) {}
