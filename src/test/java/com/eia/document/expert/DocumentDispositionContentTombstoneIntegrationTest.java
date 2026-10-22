package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the content side of disposition: a successful dispose
 * of a document with live content tombstones the current pointer and every
 * revision in the same transaction and appends the same
 * {@code document.content.deleted} fact an explicit content delete writes.
 * A document with no content or already-tombstoned content is disposed with
 * only {@code document.document.disposed}; a legal hold blocks disposition
 * before any bytes are touched; and a rollback leaves document, content, and
 * outbox rows unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentDispositionContentTombstoneIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final String TENANT_HEADER = "X-EIA-Tenant";
    private static final String FILE_NAME_HEADER = "X-File-Name";
    private static final String DELETE_EVENT = "document.content.deleted";
    private static final String DISPOSED_EVENT = "document.document.disposed";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentContentService contentService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_content_version");
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void disposeTombstonesCurrentAndVersionBytesAndWritesBothFacts() throws Exception {
        Document document = activeDocument();
        byte[] first = "first revision".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second revision is longer".getBytes(StandardCharsets.UTF_8);
        String secondSha = sha256(second);
        upload(document.id(), "first.txt", MediaType.TEXT_PLAIN_VALUE, first)
                .andExpect(status().isOk());
        upload(document.id(), "second.pdf", MediaType.APPLICATION_PDF_VALUE, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2));

        Document disposed = documentService.dispose(TENANT, document.id(), "Retention expired");

        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(disposed.disposedAt()).isNotNull();

        Map<String, Object> pointer = pointerRow(document.id());
        assertThat(pointer.get("content")).isNull();
        assertThat(pointer.get("deleted_at")).isNotNull();
        assertThat(pointer.get("file_name")).isEqualTo("second.pdf");
        assertThat(pointer.get("content_type")).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(((Number) pointer.get("size_bytes")).longValue()).isEqualTo(second.length);
        assertThat(pointer.get("sha256")).isEqualTo(secondSha);
        assertThat(((Number) pointer.get("current_version")).intValue()).isEqualTo(2);

        Map<String, Object> firstVersion = versionRow(document.id(), 1);
        assertThat(firstVersion.get("content")).isNull();
        assertThat(firstVersion.get("deleted_at")).isNotNull();
        assertThat(firstVersion.get("file_name")).isEqualTo("first.txt");

        Map<String, Object> secondVersion = versionRow(document.id(), 2);
        assertThat(secondVersion.get("content")).isNull();
        assertThat(secondVersion.get("deleted_at")).isNotNull();
        assertThat(secondVersion.get("file_name")).isEqualTo("second.pdf");
        assertThat(secondVersion.get("sha256")).isEqualTo(secondSha);

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONTENT_DELETED"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONTENT_DELETED"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 2)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONTENT_DELETED"));

        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("second.pdf"))
                .andExpect(jsonPath("$.sha256").value(secondSha))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());

        Map<String, Object> contentEvent = singleEvent(document.id(), DELETE_EVENT);
        assertThat(contentEvent.get("aggregate_type")).isEqualTo("DocumentContent");
        assertThat(((Number) contentEvent.get("aggregate_version")).intValue()).isEqualTo(2);

        JsonNode payload = objectMapper.readTree((String) contentEvent.get("payload"));
        assertThat(payload.size()).isEqualTo(6);
        assertThat(payload.get("documentId").asText()).isEqualTo(document.id().toString());
        assertThat(payload.get("documentNumber").asText()).isEqualTo(document.documentNumber());
        assertThat(payload.get("fileName").asText()).isEqualTo("second.pdf");
        assertThat(payload.get("sizeBytes").asLong()).isEqualTo(second.length);
        assertThat(payload.get("sha256").asText()).isEqualTo(secondSha);
        assertThat(payload.has("contentType")).isFalse();
        assertThat(payload.has("version")).isFalse();
        assertThat(Instant.parse(payload.get("deletedAt").asText())
                .truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(deletedAtOf(document.id()).truncatedTo(ChronoUnit.MILLIS));

        var envelope = outboxRepository.findById((UUID) contentEvent.get("id"))
                .orElseThrow().toEnvelope();
        assertThat(envelope.eventType()).isEqualTo(DELETE_EVENT);
        assertThat(envelope.aggregateType()).isEqualTo("DocumentContent");
        assertThat(envelope.aggregateId()).isEqualTo(document.id());
        assertThat(envelope.aggregateVersion()).isEqualTo(2L);
        assertThat(envelope.data()).containsOnlyKeys(
                "documentId", "documentNumber", "fileName", "sizeBytes", "sha256", "deletedAt");

        Map<String, Object> disposedEvent = singleEvent(document.id(), DISPOSED_EVENT);
        assertThat(((Number) disposedEvent.get("aggregate_version")).longValue())
                .isEqualTo(disposed.version() + 1L);
        JsonNode disposedPayload = objectMapper.readTree((String) disposedEvent.get("payload"));
        assertThat(disposedPayload.get("status").asText()).isEqualTo("DISPOSED");
        assertThat(disposedPayload.get("reason").asText()).isEqualTo("Retention expired");
        assertThat(disposedPayload.get("documentId").asText()).isEqualTo(document.id().toString());
    }

    @Test
    void disposeWithoutContentWritesOnlyTheDisposedFact() {
        Document document = activeDocument();

        Document disposed = documentService.dispose(TENANT, document.id(), "Retention expired");

        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(disposed.disposedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_content WHERE document_id = ?",
                Integer.class, document.id())).isZero();
        assertThat(events(document.id(), DELETE_EVENT)).isEmpty();
        assertThat(events(document.id(), DISPOSED_EVENT)).hasSize(1);
    }

    @Test
    void disposeAfterAnExplicitContentDeleteWritesOnlyTheDisposedFact() throws Exception {
        Document document = activeDocument();
        upload(document.id(), "already-gone.txt", MediaType.TEXT_PLAIN_VALUE,
                "already gone".getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk());
        contentService.deleteContent(TENANT, document.id());
        Instant firstTombstone = deletedAtOf(document.id());
        assertThat(firstTombstone).isNotNull();
        assertThat(events(document.id(), DELETE_EVENT)).hasSize(1);

        Document disposed = documentService.dispose(TENANT, document.id(), "Retention expired");

        assertThat(disposed.status()).isEqualTo("DISPOSED");
        assertThat(deletedAtOf(document.id())).isEqualTo(firstTombstone);
        assertThat(pointerRow(document.id()).get("content")).isNull();
        assertThat(pointerRow(document.id()).get("deleted_at")).isNotNull();
        assertThat(events(document.id(), DELETE_EVENT)).hasSize(1);
        assertThat(events(document.id(), DISPOSED_EVENT)).hasSize(1);
    }

    @Test
    void legalHoldBlocksDispositionBeforeContentIsTouched() throws Exception {
        Document document = activeDocument();
        byte[] bytes = "held evidence".getBytes(StandardCharsets.UTF_8);
        upload(document.id(), "evidence.pdf", MediaType.APPLICATION_PDF_VALUE, bytes)
                .andExpect(status().isOk());
        Document held = documentService.placeHold(TENANT, document.id(), "Litigation hold");
        assertThat(held.status()).isEqualTo("ON_HOLD");

        DocumentConflictException ex = catchThrowableOfType(
                () -> documentService.dispose(TENANT, document.id(), "Retention expired"),
                DocumentConflictException.class);

        assertThat(ex.code()).isEqualTo(DocumentRules.DOCUMENT_ON_LEGAL_HOLD);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("ON_HOLD");
        assertThat(jdbc.queryForObject(
                "SELECT disposed_at FROM document WHERE id = ?", Object.class, document.id()))
                .isNull();
        assertThat(pointerRow(document.id()).get("content")).isEqualTo(bytes);
        assertThat(pointerRow(document.id()).get("deleted_at")).isNull();
        assertThat(versionRow(document.id(), 1).get("content")).isEqualTo(bytes);
        assertThat(versionRow(document.id(), 1).get("deleted_at")).isNull();
        assertThat(events(document.id(), DELETE_EVENT)).isEmpty();
        assertThat(events(document.id(), DISPOSED_EVENT)).isEmpty();

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void disposeAndContentTombstoneRollBackTogether() throws Exception {
        Document document = activeDocument();
        byte[] bytes = "still here".getBytes(StandardCharsets.UTF_8);
        upload(document.id(), "rollback.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            documentService.dispose(TENANT, document.id(), "Retention expired");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, document.id()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT disposed_at FROM document WHERE id = ?", Object.class, document.id()))
                .isNull();
        assertThat(pointerRow(document.id()).get("content")).isEqualTo(bytes);
        assertThat(pointerRow(document.id()).get("deleted_at")).isNull();
        assertThat(versionRow(document.id(), 1).get("content")).isEqualTo(bytes);
        assertThat(versionRow(document.id(), 1).get("deleted_at")).isNull();
        assertThat(events(document.id(), DELETE_EVENT)).isEmpty();
        assertThat(events(document.id(), DISPOSED_EVENT)).isEmpty();

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    private Document createDocument() {
        return documentService.create(new CreateDocumentRequest(
                TENANT, "Claims evidence", "POLICY", "CONFIDENTIAL", TODAY));
    }

    /**
     * A disposition may only start from ACTIVE, ARCHIVED, or SUPERSEDED; drive
     * a fresh document through the lifecycle to ACTIVE with retention expiring
     * today.
     */
    private Document activeDocument() {
        Document document = createDocument();
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("SCANNING", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("PROCESSING", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("CLASSIFIED", document.version()));
        return documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("ACTIVE", document.version()));
    }

    private ResultActions upload(UUID documentId, String fileName, String contentType, byte[] bytes)
            throws Exception {
        MockHttpServletRequestBuilder request =
                put("/api/v1/documents/{id}/content", documentId)
                        .header(TENANT_HEADER, TENANT.toString())
                        .contentType(contentType)
                        .content(bytes);
        if (fileName != null) {
            request.header(FILE_NAME_HEADER, URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return mockMvc.perform(request);
    }

    private List<Map<String, Object>> events(UUID documentId, String eventType) {
        return jdbc.queryForList("""
                SELECT id, aggregate_type, aggregate_id, aggregate_version, payload
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

    private Instant deletedAtOf(UUID documentId) {
        OffsetDateTime deletedAt = jdbc.query("""
                SELECT deleted_at
                  FROM document_content
                 WHERE document_id = ?
                """, (rs, rowNum) -> rs.getObject("deleted_at", OffsetDateTime.class),
                documentId).stream().findFirst().orElse(null);
        return deletedAt == null ? null : deletedAt.toInstant();
    }

    private Map<String, Object> pointerRow(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM document_content WHERE document_id = ?", documentId);
    }

    private Map<String, Object> versionRow(UUID documentId, int version) {
        return jdbc.queryForMap("""
                SELECT * FROM document_content_version
                 WHERE document_id = ? AND version = ?
                """, documentId, version);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
