package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for automatic retention disposition: the opt-in Quartz
 * schedule, tenant-scoped due selection across the disposable statuses, legal
 * hold and future dates left untouched, and a disposition that tombstones
 * content and writes both canonical facts.
 */
@SpringBootTest(properties = "eia.document.retention.enabled=true")
@ActiveProfiles("test")
class RetentionDispositionIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    private static final String DISPOSED_EVENT = "document.document.disposed";
    private static final String CONTENT_DELETED_EVENT = "document.content.deleted";

    @Autowired
    private RetentionDispositionService retentionService;

    @Autowired
    private RetentionDispositionProperties properties;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentContentService contentService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_content_version");
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void enabledRetentionRegistersTheDailyQuartzJob() throws Exception {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.effectiveCron()).isEqualTo(RetentionDispositionProperties.DEFAULT_CRON);
        assertThat(properties.effectiveBatchSize()).isEqualTo(100);
        assertThat(properties.effectiveActorId()).isEqualTo(SYSTEM_ACTOR);

        JobKey jobKey = new JobKey(DocumentRetentionSchedulingConfiguration.JOB_NAME,
                DocumentRetentionSchedulingConfiguration.JOB_GROUP);
        TriggerKey triggerKey = new TriggerKey(DocumentRetentionSchedulingConfiguration.TRIGGER_NAME,
                DocumentRetentionSchedulingConfiguration.JOB_GROUP);

        assertThat(scheduler.getJobDetail(jobKey)).isNotNull();
        CronTrigger trigger = (CronTrigger) scheduler.getTrigger(triggerKey);
        assertThat(trigger).isNotNull();
        assertThat(trigger.getCronExpression()).isEqualTo("0 0 2 * * ?");
        assertThat(trigger.getTimeZone()).isEqualTo(TimeZone.getTimeZone("UTC"));
    }

    @Test
    void disposesDueDocumentsAcrossStatusesAndTenantsAndSkipsTheRest() {
        Document active = activeDocument(TENANT, TODAY.minusDays(2));
        Document archived = archive(activeDocument(TENANT, TODAY.minusDays(1)));
        Document superseded = supersede(activeDocument(TENANT, TODAY));
        Document otherTenant = activeDocument(OTHER_TENANT, TODAY.minusDays(3));

        Document future = activeDocument(TENANT, TODAY.plusDays(1));
        Document held = activeDocument(TENANT, TODAY.minusDays(1));
        documentService.placeHold(TENANT, held.id(), "Litigation hold");
        Document alreadyDisposed = activeDocument(TENANT, TODAY.minusDays(1));
        documentService.dispose(TENANT, alreadyDisposed.id(), "Manual disposition");

        RetentionDispositionService.RetentionDispositionSummary summary =
                retentionService.runBatch();

        assertThat(summary).isEqualTo(
                new RetentionDispositionService.RetentionDispositionSummary(4, 4, 0));
        assertThat(statusOf(active.id())).isEqualTo("DISPOSED");
        assertThat(statusOf(archived.id())).isEqualTo("DISPOSED");
        assertThat(statusOf(superseded.id())).isEqualTo("DISPOSED");
        assertThat(statusOf(otherTenant.id())).isEqualTo("DISPOSED");

        assertThat(statusOf(future.id())).isEqualTo("ACTIVE");
        assertThat(statusOf(held.id())).isEqualTo("ON_HOLD");
        assertThat(statusOf(alreadyDisposed.id())).isEqualTo("DISPOSED");

        assertThat(events(future.id(), DISPOSED_EVENT)).isEmpty();
        assertThat(events(held.id(), DISPOSED_EVENT)).isEmpty();
        assertThat(events(alreadyDisposed.id(), DISPOSED_EVENT)).hasSize(1);
        assertThat(events(active.id(), DISPOSED_EVENT)).hasSize(1);
        assertThat(events(archived.id(), DISPOSED_EVENT)).hasSize(1);
        assertThat(events(superseded.id(), DISPOSED_EVENT)).hasSize(1);
        assertThat(events(otherTenant.id(), DISPOSED_EVENT)).hasSize(1);
    }

    @Test
    void heldDocumentsAreNeverDisposedEvenWhenRetentionHasExpired() {
        Document held = activeDocument(TENANT, TODAY.minusDays(30));
        documentService.placeHold(TENANT, held.id(), "Litigation hold");

        RetentionDispositionService.RetentionDispositionSummary summary =
                retentionService.runBatch();

        assertThat(summary).isEqualTo(
                new RetentionDispositionService.RetentionDispositionSummary(0, 0, 0));
        assertThat(statusOf(held.id())).isEqualTo("ON_HOLD");
        assertThat(jdbc.queryForObject(
                "SELECT disposed_at FROM document WHERE id = ?", Object.class, held.id()))
                .isNull();
        assertThat(events(held.id(), DISPOSED_EVENT)).isEmpty();
    }

    @Test
    void futureRetentionDatesAreNotDisposed() {
        Document future = activeDocument(TENANT, TODAY.plusDays(1));

        RetentionDispositionService.RetentionDispositionSummary summary =
                retentionService.runBatch();

        assertThat(summary.selected()).isZero();
        assertThat(statusOf(future.id())).isEqualTo("ACTIVE");
        assertThat(events(future.id(), DISPOSED_EVENT)).isEmpty();
    }

    @Test
    void automaticDispositionTombstonesContentAndWritesBothFacts() throws Exception {
        Document document = activeDocument(TENANT, TODAY.minusDays(1));
        byte[] first = "first revision".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second revision is longer".getBytes(StandardCharsets.UTF_8);
        contentService.upload(TENANT, document.id(), "first.txt", "text/plain", first);
        contentService.upload(TENANT, document.id(), "second.pdf", "application/pdf", second);

        RetentionDispositionService.RetentionDispositionSummary summary =
                retentionService.runBatch();

        assertThat(summary).isEqualTo(
                new RetentionDispositionService.RetentionDispositionSummary(1, 1, 0));

        Document disposed = documentService.get(TENANT, document.id());
        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(disposed.disposedAt()).isNotNull();

        Map<String, Object> pointer = jdbc.queryForMap(
                "SELECT * FROM document_content WHERE document_id = ?", document.id());
        assertThat(pointer.get("content")).isNull();
        assertThat(pointer.get("deleted_at")).isNotNull();
        assertThat(((Number) pointer.get("current_version")).intValue()).isEqualTo(2);

        assertThat(versionRow(document.id(), 1).get("content")).isNull();
        assertThat(versionRow(document.id(), 1).get("deleted_at")).isNotNull();
        assertThat(versionRow(document.id(), 2).get("content")).isNull();
        assertThat(versionRow(document.id(), 2).get("deleted_at")).isNotNull();

        Map<String, Object> disposedEvent = singleEvent(document.id(), DISPOSED_EVENT);
        JsonNode payload = objectMapper.readTree((String) disposedEvent.get("payload"));
        assertThat(payload.get("reason").asText()).isEqualTo("RETENTION_EXPIRED");
        assertThat(payload.get("status").asText()).isEqualTo("DISPOSED");
        assertThat(payload.get("documentId").asText()).isEqualTo(document.id().toString());

        OutboxEvent disposedRow = outboxRepository.findById((UUID) disposedEvent.get("id"))
                .orElseThrow();
        assertThat(disposedRow.toEnvelope().actor().type()).isEqualTo("SYSTEM");
        assertThat(disposedRow.toEnvelope().actor().id()).isEqualTo(SYSTEM_ACTOR);

        Map<String, Object> contentEvent = singleEvent(document.id(), CONTENT_DELETED_EVENT);
        OutboxEvent contentRow = outboxRepository.findById((UUID) contentEvent.get("id"))
                .orElseThrow();
        assertThat(contentRow.toEnvelope().actor().id()).isEqualTo(SYSTEM_ACTOR);
    }

    private Document activeDocument(UUID tenantId, LocalDate retentionUntil) {
        LocalDate creationRetention = retentionUntil.isBefore(TODAY) ? TODAY : retentionUntil;
        Document document = documentService.create(new CreateDocumentRequest(
                tenantId, "Retention evidence", "POLICY", "CONFIDENTIAL", creationRetention));
        document = documentService.changeStatus(tenantId, document.id(),
                new UpdateDocumentStatusRequest("SCANNING", document.version()));
        document = documentService.changeStatus(tenantId, document.id(),
                new UpdateDocumentStatusRequest("PROCESSING", document.version()));
        document = documentService.changeStatus(tenantId, document.id(),
                new UpdateDocumentStatusRequest("CLASSIFIED", document.version()));
        document = documentService.changeStatus(tenantId, document.id(),
                new UpdateDocumentStatusRequest("ACTIVE", document.version()));
        if (!retentionUntil.equals(creationRetention)) {
            // Creation rejects an already-expired retention date, so a fixture
            // that needs a past date records the normal date first and then
            // ages the row in place.
            jdbc.update("UPDATE document SET retention_until = ? WHERE id = ?",
                    retentionUntil, document.id());
        }
        return document;
    }

    private Document archive(Document document) {
        return documentService.changeStatus(document.tenantId(), document.id(),
                new UpdateDocumentStatusRequest("ARCHIVED", document.version()));
    }

    private Document supersede(Document document) {
        return documentService.changeStatus(document.tenantId(), document.id(),
                new UpdateDocumentStatusRequest("SUPERSEDED", document.version()));
    }

    private String statusOf(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, documentId);
    }

    private List<Map<String, Object>> events(UUID documentId, String eventType) {
        return jdbc.queryForList("""
                SELECT id, event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence
                """, documentId, eventType);
    }

    private Map<String, Object> singleEvent(UUID documentId, String eventType) {
        List<Map<String, Object>> matches = events(documentId, eventType);
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private Map<String, Object> versionRow(UUID documentId, int version) {
        return jdbc.queryForMap("""
                SELECT * FROM document_content_version
                 WHERE document_id = ? AND version = ?
                """, documentId, version);
    }
}
