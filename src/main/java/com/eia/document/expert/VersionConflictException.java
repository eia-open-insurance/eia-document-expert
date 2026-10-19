package com.eia.document.expert;

/**
 * Raised when an optimistic concurrency check fails. Surfaces as HTTP 409.
 */
public class VersionConflictException extends RuntimeException {
    public VersionConflictException(String message) {
        super(message);
    }
}
