package com.eia.document.expert;

import jakarta.validation.constraints.Size;

/**
 * Optional disposition context. The reason is carried into the
 * {@code document.disposed} event; when absent the event records
 * {@code RETENTION_EXPIRED}. The approval and certificate steps from SPEC
 * 11.6.8 are out of this slice.
 */
public record DisposeDocumentRequest(
        @Size(max = DocumentRules.MAX_HOLD_REASON_LENGTH) String reason
) {}
