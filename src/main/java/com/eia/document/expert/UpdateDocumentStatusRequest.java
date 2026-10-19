package com.eia.document.expert;

import jakarta.validation.constraints.NotBlank;

/**
 * Internal request to move a document along the lifecycle. When
 * {@code expectedVersion} is supplied the update is rejected with a 409 if the
 * stored version has moved, which keeps the optimistic lock meaningful across
 * the domain-to-expert boundary.
 */
public record UpdateDocumentStatusRequest(
        @NotBlank String status,
        Integer expectedVersion
) {}
