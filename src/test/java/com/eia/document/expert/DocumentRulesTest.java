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

    private static CreateDocumentRequest request(String type, String classification, LocalDate retentionUntil) {
        return new CreateDocumentRequest(TENANT, "Title", type, classification, retentionUntil);
    }

    private static Document document(String status, LocalDate retentionUntil, int version) {
        return new Document(UUID.randomUUID(), TENANT, "DOC-2026-0000001", "Title", "POLICY",
                "CONFIDENTIAL", status, retentionUntil, version, NOW, NOW);
    }
}
