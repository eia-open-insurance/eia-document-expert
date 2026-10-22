package com.eia.document.expert;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content upload invariants shared by the API and the service.
 *
 * <ul>
 *   <li>{@code 10 MiB} is the hard ceiling for one content upload; an empty
 *       body is rejected instead of storing a zero-byte file.</li>
 *   <li>{@code X-File-Name} carries a URL-encoded filename and is decoded to
 *       UTF-8. Directory components and control characters are removed from
 *       the stored display name so it is safe in a response header.</li>
 *   <li>{@code sha256} is the lowercase hexadecimal digest of the stored
 *       bytes, which lets a caller verify a download without trusting the
 *       transport.</li>
 * </ul>
 */
public final class DocumentContentRules {
    /** 10 MiB; one byte above this is {@code 413 FILE_TOO_LARGE}. */
    public static final int MAX_CONTENT_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FILE_NAME_LENGTH = 255;
    public static final int MAX_CONTENT_TYPE_LENGTH = 128;
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    public static final String EMPTY_CONTENT = "EMPTY_CONTENT";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String FILE_NAME_REQUIRED = "FILE_NAME_REQUIRED";
    public static final String INVALID_FILE_NAME = "INVALID_FILE_NAME";
    public static final String FILE_NAME_TOO_LONG = "FILE_NAME_TOO_LONG";
    public static final String INVALID_CONTENT_TYPE = "INVALID_CONTENT_TYPE";
    public static final String CONTENT_TYPE_TOO_LONG = "CONTENT_TYPE_TOO_LONG";

    public static final String CONTENT_DELETED = "CONTENT_DELETED";
    public static final String CONTENT_ALREADY_DELETED = "CONTENT_ALREADY_DELETED";

    private DocumentContentRules() {}

    /**
     * A legal hold freezes the record (SPEC DOC-06), including its content:
     * tombstoning bytes under a hold would alter evidence. The service checks
     * this before clearing anything, so a rejected delete leaves the content
     * untouched and writes no event.
     */
    public static void validateDelete(Document document) {
        if (DocumentStatus.of(document.status()) == DocumentStatus.ON_HOLD) {
            throw new DocumentConflictException(DocumentRules.DOCUMENT_ON_LEGAL_HOLD,
                    "document " + document.id()
                            + " is under legal hold; content deletion is blocked (SPEC DOC-06)");
        }
    }

    /**
     * Rejects a zero-byte upload and anything above the 10 MiB ceiling before
     * the bytes are written.
     */
    public static void validateSize(byte[] content) {
        int size = content == null ? 0 : content.length;
        if (size == 0) {
            throw new DocumentRuleException(EMPTY_CONTENT,
                    "Uploaded content is empty; a document content body must contain at least one byte");
        }
        if (size > MAX_CONTENT_BYTES) {
            throw new FileTooLargeException(size);
        }
    }

    /**
     * Decodes the URL-encoded {@code X-File-Name} header. A malformed escape is
     * a 400, not a silent pass-through.
     */
    public static String decodeFileName(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return URLDecoder.decode(encoded.trim(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new DocumentRuleException(INVALID_FILE_NAME,
                    "X-File-Name is not valid URL-encoded text");
        }
    }

    /**
     * Normalizes the decoded filename. The header is display metadata, so a
     * client-supplied path is reduced to its last component and characters
     * that could break the download's {@code Content-Disposition} are
     * rejected. The 255-character limit matches the column.
     */
    public static String requireFileName(String decoded) {
        if (decoded == null || decoded.isBlank()) {
            throw new DocumentRuleException(FILE_NAME_REQUIRED,
                    "X-File-Name header is required and must not be blank");
        }
        String name = decoded.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank()) {
            throw new DocumentRuleException(FILE_NAME_REQUIRED,
                    "X-File-Name header must name a file");
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            throw new DocumentRuleException(FILE_NAME_TOO_LONG,
                    "X-File-Name is " + name.length() + " characters; the maximum is "
                            + MAX_FILE_NAME_LENGTH);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new DocumentRuleException(INVALID_FILE_NAME,
                        "X-File-Name must not contain control characters");
            }
        }
        return name;
    }

    /**
     * Normalizes the request {@code Content-Type} to its MIME type, defaulting
     * to {@code application/octet-stream} when the client omits it. Parameters
     * such as {@code charset} are not part of the stored type: the metadata
     * and the download response advertise the file's media type, and the bytes
     * are returned unchanged.
     */
    public static String requireContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        String header = raw.trim();
        for (int i = 0; i < header.length(); i++) {
            if (Character.isISOControl(header.charAt(i))) {
                throw new DocumentRuleException(INVALID_CONTENT_TYPE,
                        "Content-Type must not contain control characters");
            }
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(header);
        } catch (InvalidMediaTypeException ex) {
            throw new DocumentRuleException(INVALID_CONTENT_TYPE,
                    "Content-Type is not a valid MIME type");
        }
        if (mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
            throw new DocumentRuleException(INVALID_CONTENT_TYPE,
                    "Content-Type must name a concrete MIME type");
        }
        String contentType = mediaType.getType() + "/" + mediaType.getSubtype();
        if (contentType.length() > MAX_CONTENT_TYPE_LENGTH) {
            throw new DocumentRuleException(CONTENT_TYPE_TOO_LONG,
                    "Content-Type is " + contentType.length() + " characters; the maximum is "
                            + MAX_CONTENT_TYPE_LENGTH);
        }
        return contentType;
    }

    /**
     * Lowercase SHA-256 of the exact bytes being stored.
     */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM", ex);
        }
    }
}
