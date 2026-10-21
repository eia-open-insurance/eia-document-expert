package com.eia.document.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
class DocumentLegalHoldIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate RETENTION = TODAY.plusYears(10);

    @Autowired
    private DocumentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_content_version");
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void placeHoldSnapshotsThePreviousStatusAndWritesItsEvent() {
        Document document = activeDocument(RETENTION);

        Document held = service.placeHold(TENANT, document.id(), "  Litigation hold  ");

        assertThat(held.status()).isEqualTo("ON_HOLD");
        assertThat(held.holdReason()).isEqualTo("Litigation hold");
        assertThat(held.heldAt()).isNotNull();
        assertThat(held.statusBeforeHold()).isEqualTo("ACTIVE");
        assertThat(held.disposedAt()).isNull();
        assertThat(held.version()).isEqualTo(document.version() + 1);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status, hold_reason, held_at, status_before_hold, disposed_at
                  FROM document
                 WHERE id = ?
                """, document.id());
        assertThat(row.get("status")).isEqualTo("ON_HOLD");
        assertThat(row.get("hold_reason")).isEqualTo("Litigation hold");
        assertThat(row.get("held_at")).isNotNull();
        assertThat(row.get("status_before_hold")).isEqualTo("ACTIVE");
        assertThat(row.get("disposed_at")).isNull();

        List<Map<String, Object>> events = events(document, "document.legal-hold.placed");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("aggregate_version")).isEqualTo((long) held.version() + 1);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"reason\":\"Litigation hold\"")
                .contains("\"status\":\"ON_HOLD\"");
    }

    @Test
    void releaseHoldRestoresTheSnapshotAndClearsTheHoldColumns() {
        Document document = activeDocument(RETENTION);
        Document held = service.placeHold(TENANT, document.id(), "Litigation hold");

        Document released = service.releaseHold(TENANT, held.id());

        assertThat(released.status()).isEqualTo("ACTIVE");
        assertThat(released.holdReason()).isNull();
        assertThat(released.heldAt()).isNull();
        assertThat(released.statusBeforeHold()).isNull();
        assertThat(released.disposedAt()).isNull();
        assertThat(released.version()).isEqualTo(held.version() + 1);

        List<Map<String, Object>> events = events(document, "document.legal-hold.released");
        assertThat(events).hasSize(1);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"reason\":\"Litigation hold\"")
                .contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void releaseFallsBackToArchivedWhenTheSnapshotIsMissing() {
        Document document = activeDocument(RETENTION);
        Document held = service.placeHold(TENANT, document.id(), "Litigation hold");
        jdbc.update("UPDATE document SET status_before_hold = NULL WHERE id = ?", held.id());

        Document released = service.releaseHold(TENANT, held.id());

        assertThat(released.status()).isEqualTo("ARCHIVED");
        assertThat(released.statusBeforeHold()).isNull();
    }

    @Test
    void disposeBeforeRetentionExpiresIsRejectedAndWritesNoEvent() {
        Document document = activeDocument(RETENTION);

        DocumentConflictException ex = catchThrowableOfType(
                () -> service.dispose(TENANT, document.id(), "Too early"),
                DocumentConflictException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.RETENTION_NOT_EXPIRED);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT disposed_at FROM document WHERE id = ?", Object.class, document.id()))
                .isNull();
        assertThat(events(document, "document.document.disposed")).isEmpty();
    }

    @Test
    void legalHoldBlocksDispositionEvenAfterRetentionExpired() {
        Document document = activeDocument(TODAY);
        Document held = service.placeHold(TENANT, document.id(), "Litigation hold");

        DocumentConflictException ex = catchThrowableOfType(
                () -> service.dispose(TENANT, held.id(), "Retention expired"),
                DocumentConflictException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.DOCUMENT_ON_LEGAL_HOLD);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, held.id()))
                .isEqualTo("ON_HOLD");
        assertThat(events(document, "document.document.disposed")).isEmpty();
    }

    @Test
    void disposeAfterRetentionExpiryTombstonesTheDocumentAndWritesItsEvent() {
        Document document = activeDocument(TODAY);

        Document disposed = service.dispose(TENANT, document.id(), "Retention expired");

        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(disposed.disposedAt()).isNotNull();
        assertThat(disposed.version()).isEqualTo(document.version() + 1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("DISPOSED");
        assertThat(jdbc.queryForObject(
                "SELECT disposed_at FROM document WHERE id = ?", Object.class, document.id()))
                .isNotNull();

        List<Map<String, Object>> events = events(document, "document.document.disposed");
        assertThat(events).hasSize(1);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"reason\":\"Retention expired\"")
                .contains("\"status\":\"DISPOSED\"");
    }

    @Test
    void disposeDefaultsTheEventReasonWhenNoneIsSupplied() {
        Document document = activeDocument(TODAY);

        service.dispose(TENANT, document.id(), null);

        List<Map<String, Object>> events = events(document, "document.document.disposed");
        assertThat(events).hasSize(1);
        assertThat((String) events.get(0).get("payload"))
                .contains("\"reason\":\"RETENTION_EXPIRED\"");
    }

    @Test
    void anotherTenantCannotHoldOrDisposeTheDocument() {
        Document document = activeDocument(TODAY);

        assertThatThrownBy(() -> service.placeHold(OTHER_TENANT, document.id(), "Litigation hold"))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.dispose(OTHER_TENANT, document.id(), "Retention expired"))
                .isInstanceOf(DocumentNotFoundException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("ACTIVE");
        assertThat(events(document, "document.legal-hold.placed")).isEmpty();
        assertThat(events(document, "document.document.disposed")).isEmpty();
    }

    @Test
    void holdRollbackLeavesNeitherTheHoldNorTheEvent() {
        Document document = activeDocument(RETENTION);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.placeHold(TENANT, document.id(), "Litigation hold");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT hold_reason FROM document WHERE id = ?", Object.class, document.id()))
                .isNull();
        assertThat(events(document, "document.legal-hold.placed")).isEmpty();
    }

    @Test
    void disposeRollbackLeavesTheDocumentActiveAndWritesNoEvent() {
        Document document = activeDocument(TODAY);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.dispose(TENANT, document.id(), "Retention expired");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT disposed_at FROM document WHERE id = ?", Object.class, document.id()))
                .isNull();
        assertThat(events(document, "document.document.disposed")).isEmpty();
    }

    @Test
    void schemaDdlIsIdempotentForLegalHoldAndDispositionColumns() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(dataSource);

        Integer columns = jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_name = 'document'
                   AND column_name IN ('hold_reason', 'held_at', 'status_before_hold', 'disposed_at')
                """, Integer.class);
        assertThat(columns).isEqualTo(4);
    }

    private Document activeDocument(LocalDate retentionUntil) {
        Document document = service.create(new CreateDocumentRequest(
                TENANT, "Policy schedule", "POLICY", "CONFIDENTIAL", retentionUntil));
        document = service.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("SCANNING", document.version()));
        document = service.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("PROCESSING", document.version()));
        document = service.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("CLASSIFIED", document.version()));
        return service.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("ACTIVE", document.version()));
    }

    private List<Map<String, Object>> events(Document document, String eventType) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence
                """, document.id(), eventType);
    }
}
