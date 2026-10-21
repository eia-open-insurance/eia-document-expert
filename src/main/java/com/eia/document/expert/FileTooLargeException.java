package com.eia.document.expert;

/**
 * Raised when an upload exceeds the 10 MiB content ceiling. Mapped to
 * {@code 413 FILE_TOO_LARGE} rather than the generic 400 envelope.
 */
public class FileTooLargeException extends RuntimeException {
    private final long sizeBytes;

    public FileTooLargeException(long sizeBytes) {
        super("Uploaded content is " + sizeBytes + " bytes; the maximum is "
                + DocumentContentRules.MAX_CONTENT_BYTES + " bytes (10 MiB)");
        this.sizeBytes = sizeBytes;
    }

    public long sizeBytes() {
        return sizeBytes;
    }
}
