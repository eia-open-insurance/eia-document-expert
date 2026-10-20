package com.eia.document.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * API projection of a document link. {@code linkedLabel} is the stored
 * reference or, when no reference was supplied, a short form of
 * {@code linkedId} so a picker always has something to render.
 */
public record DocumentLinkDto(
        UUID id,
        UUID documentId,
        String linkedType,
        UUID linkedId,
        String linkedReference,
        String linkedLabel,
        Instant createdAt
) {
    public static DocumentLinkDto from(DocumentLink link) {
        return new DocumentLinkDto(
                link.id(),
                link.documentId(),
                link.linkedType(),
                link.linkedId(),
                link.linkedReference(),
                link.linkedLabel(),
                link.createdAt());
    }
}
