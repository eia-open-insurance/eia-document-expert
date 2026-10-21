package com.eia.document.expert;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DocumentRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);

    @Test
    void definesTheSpecificationEnumerations() {
        assertThat(DocumentType.values()).hasSize(60);
        assertThat(DocumentStatus.values()).hasSize(12);
        assertThat(Confidentiality.values()).hasSize(6);
        assertThat(DocumentType.find("PLAINTIFF_FACT_SHEET")).contains(DocumentType.PLAINTIFF_FACT_SHEET);
        assertThat(DocumentType.find("policy")).isEmpty();
        assertThat(Confidentiality.find("PHI")).contains(Confidentiality.PHI);
        assertThat(Confidentiality.find("SECRET")).isEmpty();
    }

    @Test
    void definesTheDocumentLinkTypes() {
        assertThat(LinkedType.values()).hasSize(7);
        assertThat(LinkedType.find("DIRECT_CLAIM")).contains(LinkedType.DIRECT_CLAIM);
        assertThat(LinkedType.find("OTHER")).contains(LinkedType.OTHER);
        assertThat(LinkedType.find("direct_claim")).isEmpty();
        assertThat(LinkedType.names()).contains("ASSUMED_CLAIM", "PAYMENT", "OTHER");
    }

    @Test
    void followsTheSpecificationLifecycleEdges() {
        assertThat(DocumentStatus.PENDING_UPLOAD.allowedNext()).containsExactly(DocumentStatus.RECEIVED);
        assertThat(DocumentStatus.RECEIVED.canTransitionTo(DocumentStatus.SCANNING)).isTrue();
        assertThat(DocumentStatus.RECEIVED.canTransitionTo(DocumentStatus.ACTIVE)).isFalse();
        assertThat(DocumentStatus.SCANNING.allowedNext())
                .containsExactlyInAnyOrder(DocumentStatus.PROCESSING, DocumentStatus.REJECTED);
        assertThat(DocumentStatus.PROCESSING.allowedNext())
                .containsExactlyInAnyOrder(DocumentStatus.CLASSIFIED, DocumentStatus.FAILED);
        assertThat(DocumentStatus.FAILED.canTransitionTo(DocumentStatus.PROCESSING)).isTrue();
        assertThat(DocumentStatus.ACTIVE.allowedNext()).containsExactlyInAnyOrder(
                DocumentStatus.SUPERSEDED, DocumentStatus.ON_HOLD, DocumentStatus.ARCHIVED, DocumentStatus.DISPOSED);
        assertThat(DocumentStatus.ON_HOLD.allowedNext()).containsExactly(DocumentStatus.ACTIVE);
        assertThat(DocumentStatus.ARCHIVED.allowedNext())
                .containsExactlyInAnyOrder(DocumentStatus.DISPOSED, DocumentStatus.ON_HOLD);
        assertThat(DocumentStatus.DISPOSED.isTerminal()).isTrue();
        assertThat(DocumentStatus.REJECTED.isTerminal()).isTrue();
        assertThat(DocumentStatus.SUPERSEDED.isTerminal()).isTrue();
    }

    @Test
    void acceptsRetentionOnTheCreationDate() {
        DocumentRules.validateCreate(request("POLICY", "INTERNAL", LocalDate.of(2026, 10, 19)), NOW);
    }

    @Test
    void rejectsRetentionBeforeTheCreationDate() {
        DocumentRuleException ex = catchThrowableOfType(
                () -> DocumentRules.validateCreate(request("POLICY", "INTERNAL", LocalDate.of(2026, 10, 18)), NOW),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.RETENTION_BEFORE_CREATION);
    }

    @Test
    void rejectsUnknownTypeAndClassification() {
        DocumentRuleException type = catchThrowableOfType(
                () -> DocumentRules.validateCreate(request("NOT_A_TYPE", "INTERNAL", null), NOW),
                DocumentRuleException.class);
        assertThat(type.code()).isEqualTo(DocumentRules.INVALID_DOCUMENT_TYPE);

        DocumentRuleException classification = catchThrowableOfType(
                () -> DocumentRules.validateCreate(request("POLICY", "TOP_SECRET", null), NOW),
                DocumentRuleException.class);
        assertThat(classification.code()).isEqualTo(DocumentRules.INVALID_CLASSIFICATION);
    }

    @Test
    void requiresRetentionBeforeActive() {
        Document classified = document("CLASSIFIED", null, 0);

        DocumentRuleException ex = catchThrowableOfType(
                () -> DocumentRules.validateTransition(classified, "ACTIVE", null),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.RETENTION_REQUIRED_FOR_ACTIVE);
    }

    @Test
    void rejectsUnknownTargetStatus() {
        DocumentRuleException ex = catchThrowableOfType(
                () -> DocumentRules.validateTransition(document("RECEIVED", null, 0), "ARCHIVED_TOO_SOON", null),
                DocumentRuleException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.INVALID_STATUS);
    }

    @Test
    void rejectsStaleVersion() {
        VersionConflictException ex = catchThrowableOfType(
                () -> DocumentRules.validateTransition(document("ACTIVE", LocalDate.of(2036, 10, 19), 3), "ARCHIVED", 2),
                VersionConflictException.class);

        assertThat(ex.getMessage()).contains("expected version 2").contains("version 3");
    }

    @Test
    void placesHoldFromEveryEligibleStatus() {
        for (String status : new String[]{"ACTIVE", "ARCHIVED", "CLASSIFIED", "SUPERSEDED"}) {
            DocumentRules.validateHold(document(status, RETENTION, 1), "Litigation hold");
        }
    }

    @Test
    void rejectsHoldOnHeldOrIneligibleDocument() {
        DocumentConflictException alreadyHeld = catchThrowableOfType(
                () -> DocumentRules.validateHold(document("ON_HOLD", RETENTION, 1), "Litigation hold"),
                DocumentConflictException.class);
        assertThat(alreadyHeld.code()).isEqualTo(DocumentRules.DOCUMENT_ALREADY_ON_HOLD);

        DocumentConflictException notEligible = catchThrowableOfType(
                () -> DocumentRules.validateHold(document("RECEIVED", RETENTION, 1), "Litigation hold"),
                DocumentConflictException.class);
        assertThat(notEligible.code()).isEqualTo(DocumentRules.HOLD_STATUS_NOT_ALLOWED);
    }

    @Test
    void validatesTheHoldReason() {
        DocumentRules.validateHoldReason("x".repeat(DocumentRules.MAX_HOLD_REASON_LENGTH));

        DocumentRuleException blank = catchThrowableOfType(
                () -> DocumentRules.validateHoldReason("  "), DocumentRuleException.class);
        assertThat(blank.code()).isEqualTo(DocumentRules.INVALID_HOLD_REASON);

        DocumentRuleException tooLong = catchThrowableOfType(
                () -> DocumentRules.validateHoldReason("x".repeat(DocumentRules.MAX_HOLD_REASON_LENGTH + 1)),
                DocumentRuleException.class);
        assertThat(tooLong.code()).isEqualTo(DocumentRules.INVALID_HOLD_REASON);
    }

    @Test
    void restoresTheSnapshottedStatusOrFallsBackToArchived() {
        assertThat(DocumentRules.restoredStatus(heldDocument("CLASSIFIED")))
                .isEqualTo(DocumentStatus.CLASSIFIED);
        assertThat(DocumentRules.restoredStatus(heldDocument("SUPERSEDED")))
                .isEqualTo(DocumentStatus.SUPERSEDED);
        assertThat(DocumentRules.restoredStatus(heldDocument(null)))
                .isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(DocumentRules.restoredStatus(heldDocument("NOT_A_STATUS")))
                .isEqualTo(DocumentStatus.ARCHIVED);
    }

    @Test
    void rejectsReleaseWhenTheDocumentIsNotHeld() {
        DocumentConflictException ex = catchThrowableOfType(
                () -> DocumentRules.validateRelease(document("ACTIVE", RETENTION, 1)),
                DocumentConflictException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.DOCUMENT_NOT_ON_HOLD);
    }

    @Test
    void rejectsDispositionBeforeRetentionExpires() {
        DocumentConflictException ex = catchThrowableOfType(
                () -> DocumentRules.validateDispose(document("ACTIVE", LocalDate.of(2036, 10, 19), 1),
                        LocalDate.of(2026, 10, 19)),
                DocumentConflictException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.RETENTION_NOT_EXPIRED);
    }

    @Test
    void rejectsDispositionUnderLegalHold() {
        DocumentConflictException ex = catchThrowableOfType(
                () -> DocumentRules.validateDispose(heldDocument("ACTIVE"),
                        LocalDate.of(2026, 10, 19)),
                DocumentConflictException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.DOCUMENT_ON_LEGAL_HOLD);
    }

    @Test
    void disposesOnlyEligibleStatusesWithAnExpiredRetentionDate() {
        LocalDate today = LocalDate.of(2026, 10, 19);
        DocumentRules.validateDispose(document("ACTIVE", today, 1), today);
        DocumentRules.validateDispose(document("ARCHIVED", today.minusYears(1), 1), today);
        DocumentRules.validateDispose(document("SUPERSEDED", today.minusYears(1), 1), today);

        DocumentConflictException classified = catchThrowableOfType(
                () -> DocumentRules.validateDispose(document("CLASSIFIED", today, 1), today),
                DocumentConflictException.class);
        assertThat(classified.code()).isEqualTo(DocumentRules.DISPOSAL_STATUS_NOT_ALLOWED);

        DocumentConflictException noRetention = catchThrowableOfType(
                () -> DocumentRules.validateDispose(document("ACTIVE", null, 1), today),
                DocumentConflictException.class);
        assertThat(noRetention.code()).isEqualTo(DocumentRules.RETENTION_DATE_REQUIRED);
    }

    private static CreateDocumentRequest request(String type, String classification, LocalDate retentionUntil) {
        return new CreateDocumentRequest(TENANT, "Title", type, classification, retentionUntil);
    }

    private static Document document(String status, LocalDate retentionUntil, int version) {
        return new Document(UUID.randomUUID(), TENANT, "DOC-2026-0000001", "Title", "POLICY",
                "CONFIDENTIAL", status, retentionUntil, null, null, null, null, version, NOW, NOW);
    }

    private static Document heldDocument(String statusBeforeHold) {
        return new Document(UUID.randomUUID(), TENANT, "DOC-2026-0000001", "Title", "POLICY",
                "CONFIDENTIAL", "ON_HOLD", RETENTION, "Litigation hold", NOW,
                statusBeforeHold, null, 3, NOW, NOW);
    }
}
