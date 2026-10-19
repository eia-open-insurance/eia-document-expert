package com.eia.document.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Document invariants from SPEC section 11.6.
 *
 * <ul>
 *   <li>{@code DOC-TYPE}: {@code document_type} MUST be a value from the
 *       DocumentType enumeration (SPEC 11.6.5).</li>
 *   <li>{@code DOC-CLASS}: {@code classification} MUST be a value from the
 *       Confidentiality enumeration (SPEC 11.6.5).</li>
 *   <li>{@code DOC-RET-01}: a retention date MUST NOT fall before the document
 *       creation date; retention cannot already be expired when the document is
 *       registered.</li>
 *   <li>{@code DOC-05} (slice form): a document MUST carry its retention date
 *       before it becomes ACTIVE.</li>
 *   <li>{@code DOC-06}: the lifecycle may only follow edges drawn in SPEC
 *       11.6.6; terminal states cannot leave themselves.</li>
 * </ul>
 */
public final class DocumentRules {
    public static final String INVALID_DOCUMENT_TYPE = "INVALID_DOCUMENT_TYPE";
    public static final String INVALID_CLASSIFICATION = "INVALID_CLASSIFICATION";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String RETENTION_BEFORE_CREATION = "RETENTION_BEFORE_CREATION";
    public static final String RETENTION_REQUIRED_FOR_ACTIVE = "RETENTION_REQUIRED_FOR_ACTIVE";

    private DocumentRules() {}

    public static void validateCreate(CreateDocumentRequest request, Instant now) {
        if (DocumentType.find(request.documentType()).isEmpty()) {
            throw new DocumentRuleException(INVALID_DOCUMENT_TYPE,
                    "documentType '" + request.documentType()
                            + "' is not defined by SPEC 11.6.5; expected one of: " + DocumentType.names());
        }
        if (Confidentiality.find(request.classification()).isEmpty()) {
            throw new DocumentRuleException(INVALID_CLASSIFICATION,
                    "classification '" + request.classification()
                            + "' is not defined by SPEC 11.6.5; expected one of: " + Confidentiality.names());
        }
        if (request.retentionUntil() != null) {
            LocalDate createdDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (request.retentionUntil().isBefore(createdDate)) {
                throw new DocumentRuleException(RETENTION_BEFORE_CREATION,
                        "retentionUntil " + request.retentionUntil()
                                + " is before the document creation date " + createdDate);
            }
        }
    }

    public static void validateTransition(Document current, String targetRaw, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new VersionConflictException("expected version " + expectedVersion
                    + " but document " + current.id() + " is at version " + current.version());
        }
        DocumentStatus from = DocumentStatus.of(current.status());
        DocumentStatus target = DocumentStatus.find(targetRaw)
                .orElseThrow(() -> new DocumentRuleException(INVALID_STATUS,
                        "status '" + targetRaw + "' is not defined by SPEC 11.6.5; expected one of: "
                                + DocumentStatus.names()));
        if (!from.canTransitionTo(target)) {
            throw new DocumentRuleException(INVALID_STATUS_TRANSITION,
                    from + " cannot transition to " + target
                            + "; allowed next status: " + from.allowedNext());
        }
        if (target == DocumentStatus.ACTIVE && current.retentionUntil() == null) {
            throw new DocumentRuleException(RETENTION_REQUIRED_FOR_ACTIVE,
                    "document " + current.id()
                            + " requires a retention date before it becomes ACTIVE (SPEC DOC-05)");
        }
    }
}
