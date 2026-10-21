package com.eia.document.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to place a legal hold on one document. The reason is recorded on the
 * document and in the {@code document.legal-hold.placed} event.
 */
public record HoldDocumentRequest(
        @NotBlank @Size(max = DocumentRules.MAX_HOLD_REASON_LENGTH) String reason
) {}
