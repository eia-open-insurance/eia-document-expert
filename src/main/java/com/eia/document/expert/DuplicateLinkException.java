package com.eia.document.expert;

import java.util.UUID;

/**
 * Raised when the same document is already associated with the same subject.
 * The database unique key is the final guard; the service maps it to a 409.
 */
public class DuplicateLinkException extends RuntimeException {
    public DuplicateLinkException(UUID documentId, String linkedType, UUID linkedId) {
        super("Document " + documentId + " is already linked to " + linkedType + " " + linkedId);
    }
}
