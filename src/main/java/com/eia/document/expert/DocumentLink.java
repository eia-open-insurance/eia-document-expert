package com.eia.document.expert;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One association from a document to another domain record.
 *
 * <p>The row deliberately does not duplicate the target's data: the vertical
 * that owns the target remains authoritative. {@code linkedReference} is a
 * short human-readable snapshot (a claim number, policy number, and so on) used
 * for lists and pickers.</p>
 */
public record DocumentLink(
        UUID id,
        UUID documentId,
        String linkedType,
        UUID linkedId,
        String linkedReference,
        Instant createdAt
) {
    public DocumentLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(linkedType, "linkedType");
        Objects.requireNonNull(linkedId, "linkedId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Human-readable label for the DTO: the stored reference when present,
     * otherwise a short form of the target id.
     */
    public String linkedLabel() {
        if (linkedReference != null && !linkedReference.isBlank()) {
            return linkedReference;
        }
        return linkedId.toString().substring(0, 8);
    }
}
