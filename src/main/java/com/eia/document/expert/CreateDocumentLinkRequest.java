package com.eia.document.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to associate a document with another domain record.
 *
 * <p>{@code linkedId} is required for every type, including {@code OTHER}, for
 * a single simple contract. {@code linkedReference} is an optional display
 * snapshot and is never used to resolve the target.</p>
 */
public record CreateDocumentLinkRequest(
        @NotBlank @Size(max = 32) String linkedType,
        @NotNull UUID linkedId,
        @Size(max = 64) String linkedReference
) {}
