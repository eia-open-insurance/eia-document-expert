package com.eia.document.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

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
 *       11.6.6; terminal states cannot leave themselves. A legal hold also
 *       overrides disposition and is itself reversible.</li>
 *   <li>{@code DOC-15} (slice form): disposition requires an expired retention
 *       date; the approved disposition job and certificate are out of this
 *       slice.</li>
 * </ul>
 */
public final class DocumentRules {
    public static final String INVALID_DOCUMENT_TYPE = "INVALID_DOCUMENT_TYPE";
    public static final String INVALID_CLASSIFICATION = "INVALID_CLASSIFICATION";
    public static final String INVALID_STATUS = "INVALID_STATUS";
    public static final String INVALID_STATUS_TRANSITION = "INVALID_STATUS_TRANSITION";
    public static final String RETENTION_BEFORE_CREATION = "RETENTION_BEFORE_CREATION";
    public static final String RETENTION_REQUIRED_FOR_ACTIVE = "RETENTION_REQUIRED_FOR_ACTIVE";
    public static final String INVALID_LINKED_TYPE = "INVALID_LINKED_TYPE";
    public static final String INVALID_LINK_FILTER = "INVALID_LINK_FILTER";

    public static final String INVALID_HOLD_REASON = "INVALID_HOLD_REASON";
    public static final String HOLD_STATUS_NOT_ALLOWED = "HOLD_STATUS_NOT_ALLOWED";
    public static final String DOCUMENT_ALREADY_ON_HOLD = "DOCUMENT_ALREADY_ON_HOLD";
    public static final String DOCUMENT_NOT_ON_HOLD = "DOCUMENT_NOT_ON_HOLD";
    public static final String DOCUMENT_ON_LEGAL_HOLD = "DOCUMENT_ON_LEGAL_HOLD";
    public static final String DISPOSAL_STATUS_NOT_ALLOWED = "DISPOSAL_STATUS_NOT_ALLOWED";
    public static final String RETENTION_DATE_REQUIRED = "RETENTION_DATE_REQUIRED";
    public static final String RETENTION_NOT_EXPIRED = "RETENTION_NOT_EXPIRED";

    /** A hold may be placed from these states even when the generic lifecycle
     *  diagram has no edge (CLASSIFIED, SUPERSEDED). */
    public static final Set<DocumentStatus> HOLDABLE_STATUSES = Set.of(
            DocumentStatus.ACTIVE,
            DocumentStatus.ARCHIVED,
            DocumentStatus.CLASSIFIED,
            DocumentStatus.SUPERSEDED);

    /** A disposition may start from these states. {@code ON_HOLD} is excluded
     *  explicitly so DOC-06 wins over the retention clock. */
    public static final Set<DocumentStatus> DISPOSABLE_STATUSES = Set.of(
            DocumentStatus.ACTIVE,
            DocumentStatus.ARCHIVED,
            DocumentStatus.SUPERSEDED);

    public static final int MAX_HOLD_REASON_LENGTH = 500;

    private DocumentRules() {}

    /**
     * Resolves the closed link-type vocabulary. The API accepts the enum name
     * exactly, matching the other enumerations in this vertical.
     */
    public static LinkedType requireLinkedType(String raw) {
        return LinkedType.find(raw).orElseThrow(() -> new DocumentRuleException(
                INVALID_LINKED_TYPE,
                "linkedType '" + raw + "' is not defined by the document contract; expected one of: "
                        + LinkedType.names()));
    }

    /**
     * A list filter is either absent or a complete {@code linkedType} +
     * {@code linkedId} pair; half a pair would silently widen the result.
     */
    public static void validateLinkFilter(String linkedType, UUID linkedId) {
        if ((linkedType == null) != (linkedId == null)) {
            throw new DocumentRuleException(INVALID_LINK_FILTER,
                    "linkedType and linkedId must be supplied together");
        }
    }

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

    /**
     * A hold suspends the retention clock. It may be placed from ACTIVE,
     * ARCHIVED, CLASSIFIED, or SUPERSEDED; a second hold on a held document is a
     * state conflict rather than a new snapshot.
     */
    public static void validateHold(Document current, String reason) {
        validateHoldReason(reason);
        DocumentStatus status = DocumentStatus.of(current.status());
        if (status == DocumentStatus.ON_HOLD) {
            throw new DocumentConflictException(DOCUMENT_ALREADY_ON_HOLD,
                    "document " + current.id() + " is already ON_HOLD");
        }
        if (!HOLDABLE_STATUSES.contains(status)) {
            throw new DocumentConflictException(HOLD_STATUS_NOT_ALLOWED,
                    "document " + current.id() + " is " + status
                            + "; a legal hold may be placed only on " + statusNames(HOLDABLE_STATUSES));
        }
    }

    public static void validateHoldReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DocumentRuleException(INVALID_HOLD_REASON,
                    "reason is required when placing a legal hold");
        }
        if (reason.length() > MAX_HOLD_REASON_LENGTH) {
            throw new DocumentRuleException(INVALID_HOLD_REASON,
                    "reason must be at most " + MAX_HOLD_REASON_LENGTH + " characters");
        }
    }

    /**
     * Only a document that is currently ON_HOLD can be released. Releasing an
     * unheld document is a state conflict.
     */
    public static void validateRelease(Document current) {
        if (DocumentStatus.of(current.status()) != DocumentStatus.ON_HOLD) {
            throw new DocumentConflictException(DOCUMENT_NOT_ON_HOLD,
                    "document " + current.id() + " is " + current.status()
                            + ", not ON_HOLD; there is no legal hold to release");
        }
    }

    /**
     * The status a release restores. The snapshot is authoritative; an absent or
     * unrecognised snapshot falls back to ARCHIVED so a release always lands in
     * a retrievable, non-active record state.
     */
    public static DocumentStatus restoredStatus(Document current) {
        return DocumentStatus.find(current.statusBeforeHold())
                .filter(HOLDABLE_STATUSES::contains)
                .orElse(DocumentStatus.ARCHIVED);
    }

    /**
     * Disposition requires an expired retention date and a document that is not
     * under legal hold (SPEC DOC-06, DOC-15). The status must be one the
     * disposition job may act on.
     */
    public static void validateDispose(Document current, LocalDate today) {
        DocumentStatus status = DocumentStatus.of(current.status());
        if (status == DocumentStatus.ON_HOLD) {
            throw new DocumentConflictException(DOCUMENT_ON_LEGAL_HOLD,
                    "document " + current.id()
                            + " is under legal hold; disposition is blocked (SPEC DOC-06)");
        }
        if (!DISPOSABLE_STATUSES.contains(status)) {
            throw new DocumentConflictException(DISPOSAL_STATUS_NOT_ALLOWED,
                    "document " + current.id() + " is " + status
                            + "; disposition may start only from " + statusNames(DISPOSABLE_STATUSES));
        }
        if (current.retentionUntil() == null) {
            throw new DocumentConflictException(RETENTION_DATE_REQUIRED,
                    "document " + current.id()
                            + " has no retention date; disposition requires an expired retention period");
        }
        if (current.retentionUntil().isAfter(today)) {
            throw new DocumentConflictException(RETENTION_NOT_EXPIRED,
                    "document " + current.id() + " is retained until " + current.retentionUntil()
                            + "; disposition is not allowed before " + today);
        }
    }

    private static String statusNames(Set<DocumentStatus> statuses) {
        return String.join(", ", statuses.stream().sorted().map(Enum::name).toList());
    }
}
