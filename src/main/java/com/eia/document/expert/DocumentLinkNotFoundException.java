package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when a link id is not present under the requested tenant and
 * document. The response is 404 so a caller cannot probe another tenant's or
 * another document's links.
 */
public class DocumentLinkNotFoundException extends RuntimeException {
    public DocumentLinkNotFoundException(UUID linkId) {
        super("Document link not found: " + linkId);
    }
}
