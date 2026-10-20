package com.eia.document.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DocumentOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);

    @Autowired
    private DocumentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void createWritesTheDocumentAndCreatedEventTogether() {
        Document document = service.create(request());

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_version, status, tenant_id,
                       payload, headers, partition_key, occurred_at, recorded_at, next_attempt_at
                  FROM outbox_event
                 WHERE aggregate_id = ?
                """, document.id());

        assertThat(event.get("event_type")).isEqualTo("document.document.created");
        assertThat(event.get("aggregate_type")).isEqualTo("Document");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(document.id().toString());
        assertThat(event.get("occurred_at")).isNotNull();
        assertThat(event.get("recorded_at")).isNotNull();
        assertThat(event.get("next_attempt_at")).isNotNull();

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"title\":\"Policy schedule\"")
                .contains("\"documentType\":\"POLICY\"")
                .contains("\"classification\":\"CONFIDENTIAL\"")
                .contains("\"status\":\"RECEIVED\"")
                .contains("\"retentionUntil\":\"2036-10-19\"");
        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"document\"")
                .contains("\"businessKey\":\"" + document.documentNumber() + "\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createRollbackLeavesNeitherTheDocumentNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(request());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("document")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statusChangeWritesUpdatedAndStatusChangedEventsInTheSameTransaction() {
        Document document = service.create(request());

        Document updated = service.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("SCANNING", 0));

        assertThat(updated.status()).isEqualTo("SCANNING");
        assertThat(updated.version()).isEqualTo(1);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, document.id());

        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("event_type")).isEqualTo("document.document.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("document.document.updated");
        assertThat(events.get(2).get("event_type")).isEqualTo("document.document.status-changed");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);
        assertThat(events.get(2).get("aggregate_version")).isEqualTo(2L);

        String updatedPayload = (String) events.get(1).get("payload");
        assertThat(updatedPayload)
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"version\":1")
                .contains("\"from\":\"RECEIVED\"")
                .contains("\"to\":\"SCANNING\"");
        String statusPayload = (String) events.get(2).get("payload");
        assertThat(statusPayload)
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"status\":\"SCANNING\"")
                .contains("\"from\":\"RECEIVED\"")
                .contains("\"to\":\"SCANNING\"");
    }

    @Test
    void statusRollbackKeepsTheOldStateAndWritesNoEvent() {
        Document document = service.create(request());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.changeStatus(TENANT, document.id(),
                    new UpdateDocumentStatusRequest("SCANNING", 0));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM document WHERE id = ?", Integer.class, document.id()))
                .isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ?
                   AND event_type IN ('document.document.updated', 'document.document.status-changed')
                """, Integer.class, document.id())).isZero();
    }

    private CreateDocumentRequest request() {
        return new CreateDocumentRequest(TENANT, "Policy schedule", "POLICY", "CONFIDENTIAL", RETENTION);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
