package com.eia.document.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal request to register a document. The expert owns the generated
 * document number, initial lifecycle status, and audit timestamps.
 */
public record CreateDocumentRequest(
        @NotNull UUID tenantId,
        @NotBlank @Size(max = 300) String title,
        @NotBlank String documentType,
        @NotBlank String classification,
        LocalDate retentionUntil
) {}
