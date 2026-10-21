package com.eia.document.expert;

/**
 * A lifecycle request that conflicts with the stored document state: a hold on
 * a document already held, a release of a document that is not held, or a
 * disposition blocked by the hold or retention state. Surfaces as a structured
 * {@code 409} response.
 */
public class DocumentConflictException extends RuntimeException {
    private final String code;

    public DocumentConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
