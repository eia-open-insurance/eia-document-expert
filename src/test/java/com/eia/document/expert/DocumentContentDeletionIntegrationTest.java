package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for content tombstoning against H2 in PostgreSQL mode:
 * the DELETE route, legal-hold blocking, the 410 download response, retained
 * metadata, the repeated-delete conflict, the tenant wall, the
 * {@code document.content.deleted} outbox fact, idempotent DDL, and the
 * re-upload path that clears the pointer tombstone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentContentDeletionIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);
    private static final String TENANT_HEADER = "X-EIA-Tenant";
    private static final String FILE_NAME_HEADER = "X-File-Name";
    private static final String DELETE_EVENT = "document.content.deleted";

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
    private DataSource dataSource;

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
    void deleteTombstonesCurrentAndAllVersionsButKeepsMetadata() throws Exception {
        Document document = createDocument(TENANT);
        byte[] first = "first revision".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second revision is longer".getBytes(StandardCharsets.UTF_8);
        String firstSha = sha256(first);
        String secondSha = sha256(second);
        upload(TENANT, document.id(), "first.txt", MediaType.TEXT_PLAIN_VALUE, first)
                .andExpect(status().isOk());
        upload(TENANT, document.id(), "second.pdf", MediaType.APPLICATION_PDF_VALUE, second)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.deletedAt").value(nullValue()));

        deleteContent(TENANT, document.id())
                .andExpect(status().isNoContent());

        Map<String, Object> pointer = pointerRow(document.id());
        assertThat(pointer.get("content")).isNull();
        assertThat(pointer.get("deleted_at")).isNotNull();
        assertThat(pointer.get("file_name")).isEqualTo("second.pdf");
        assertThat(pointer.get("content_type")).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(((Number) pointer.get("size_bytes")).longValue()).isEqualTo(second.length);
        assertThat(pointer.get("sha256")).isEqualTo(secondSha);
        assertThat(((Number) pointer.get("current_version")).intValue()).isEqualTo(2);
        assertThat(pointer.get("uploaded_at")).isNotNull();

        Map<String, Object> versionOne = versionRow(document.id(), 1);
        assertThat(versionOne.get("content")).isNull();
        assertThat(versionOne.get("deleted_at")).isNotNull();
        assertThat(versionOne.get("file_name")).isEqualTo("first.txt");
        assertThat(versionOne.get("content_type")).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
        assertThat(((Number) versionOne.get("size_bytes")).longValue()).isEqualTo(first.length);
        assertThat(versionOne.get("sha256")).isEqualTo(firstSha);

        Map<String, Object> versionTwo = versionRow(document.id(), 2);
        assertThat(versionTwo.get("content")).isNull();
        assertThat(versionTwo.get("deleted_at")).isNotNull();
        assertThat(versionTwo.get("file_name")).isEqualTo("second.pdf");
        assertThat(((Number) versionTwo.get("size_bytes")).longValue()).isEqualTo(second.length);
        assertThat(versionTwo.get("sha256")).isEqualTo(secondSha);

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
                .andExpect(jsonPath("$.documentId").value(document.id().toString()))
                .andExpect(jsonPath("$.fileName").value("second.pdf"))
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(second.length))
                .andExpect(jsonPath("$.sha256").value(secondSha))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty())
                .andExpect(jsonPath("$.content").doesNotExist());

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].fileName").value("second.pdf"))
                .andExpect(jsonPath("$[0].sha256").value(secondSha))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].fileName").value("first.txt"))
                .andExpect(jsonPath("$[1].sha256").value(firstSha));
    }

    @Test
    void legalHoldBlocksContentDeletionAndLeavesTheBytesIntact() throws Exception {
        Document document = activeDocument();
        byte[] bytes = "held evidence".getBytes(StandardCharsets.UTF_8);
        upload(TENANT, document.id(), "evidence.pdf", MediaType.APPLICATION_PDF_VALUE, bytes)
                .andExpect(status().isOk());
        documentService.placeHold(TENANT, document.id(), "Litigation hold");

        deleteContent(TENANT, document.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ON_LEGAL_HOLD"));

        Map<String, Object> pointer = pointerRow(document.id());
        assertThat(pointer.get("content")).isEqualTo(bytes);
        assertThat(pointer.get("deleted_at")).isNull();
        assertThat(countEvents(DELETE_EVENT)).isZero();

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.deletedAt").value(nullValue()));
    }

    @Test
    void repeatedDeleteIsConflictAndKeepsTheFirstTombstone() throws Exception {
        Document document = createDocument(TENANT);
        upload(TENANT, document.id(), "repeat.txt", MediaType.TEXT_PLAIN_VALUE,
                "repeat".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());

        deleteContent(TENANT, document.id()).andExpect(status().isNoContent());
        Instant firstDeletedAt = deletedAtOf(document.id());

        deleteContent(TENANT, document.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTENT_ALREADY_DELETED"));

        assertThat(deletedAtOf(document.id())).isEqualTo(firstDeletedAt);
        assertThat(countEvents(DELETE_EVENT)).isEqualTo(1);
    }

    @Test
    void deleteWithoutStoredContentIs404() throws Exception {
        Document document = createDocument(TENANT);

        deleteContent(TENANT, document.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));

        assertThat(countEvents(DELETE_EVENT)).isZero();
    }

    @Test
    void contentDeleteIsTenantScoped() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "tenant secret".getBytes(StandardCharsets.UTF_8);
        upload(TENANT, document.id(), "secret.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk());

        deleteContent(OTHER_TENANT, document.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        deleteContent(null, document.id())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        deleteContent(TENANT, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(pointerRow(document.id()).get("deleted_at")).isNull();
        assertThat(pointerRow(document.id()).get("content")).isEqualTo(bytes);
        assertThat(countEvents(DELETE_EVENT)).isZero();

        mockMvc.perform(delete("/api/v1/documents/{id}/content", document.id())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNoContent());
        assertThat(pointerRow(document.id()).get("deleted_at")).isNotNull();
    }

    @Test
    void deleteWritesTheContentDeletedOutboxFact() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "event evidence".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        upload(TENANT, document.id(), "evidence.pdf", MediaType.APPLICATION_PDF_VALUE, bytes)
                .andExpect(status().isOk());

        deleteContent(TENANT, document.id()).andExpect(status().isNoContent());

        List<Map<String, Object>> events = events(document.id(), DELETE_EVENT);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("aggregate_type")).isEqualTo("DocumentContent");
        assertThat(event.get("aggregate_id")).isEqualTo(document.id());
        assertThat(((Number) event.get("aggregate_version")).intValue()).isEqualTo(1);

        JsonNode payload = objectMapper.readTree((String) event.get("payload"));
        assertThat(payload.size()).isEqualTo(6);
        assertThat(payload.get("documentId").asText()).isEqualTo(document.id().toString());
        assertThat(payload.get("documentNumber").asText()).isEqualTo(document.documentNumber());
        assertThat(payload.get("fileName").asText()).isEqualTo("evidence.pdf");
        assertThat(payload.get("sizeBytes").asLong()).isEqualTo(bytes.length);
        assertThat(payload.get("sha256").asText()).isEqualTo(sha);
        assertThat(payload.has("contentType")).isFalse();
        assertThat(payload.has("version")).isFalse();
        assertThat(Instant.parse(payload.get("deletedAt").asText())
                .truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(deletedAtOf(document.id()).truncatedTo(ChronoUnit.MILLIS));

        var envelope = outboxRepository.findById((UUID) event.get("id")).orElseThrow().toEnvelope();
        assertThat(envelope.eventType()).isEqualTo(DELETE_EVENT);
        assertThat(envelope.aggregateType()).isEqualTo("DocumentContent");
        assertThat(envelope.aggregateId()).isEqualTo(document.id());
        assertThat(envelope.aggregateVersion()).isEqualTo(1);
        assertThat(envelope.data()).containsOnlyKeys(
                "documentId", "documentNumber", "fileName", "sizeBytes", "sha256", "deletedAt");
        assertThat(envelope.data())
                .containsEntry("documentId", document.id().toString())
                .containsEntry("documentNumber", document.documentNumber())
                .containsEntry("fileName", "evidence.pdf")
                .containsEntry("sha256", sha);
        assertThat(((Number) envelope.data().get("sizeBytes")).longValue()).isEqualTo(bytes.length);
        assertThat(Instant.parse((String) envelope.data().get("deletedAt")))
                .isEqualTo(Instant.parse(payload.get("deletedAt").asText()));
        assertThat((String) event.get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"document\"");
    }

    @Test
    void uploadAfterTombstoneClearsThePointerAndRestoresDownloadableContent() throws Exception {
        Document document = createDocument(TENANT);
        upload(TENANT, document.id(), "old.txt", MediaType.TEXT_PLAIN_VALUE,
                "old bytes".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());
        deleteContent(TENANT, document.id()).andExpect(status().isNoContent());

        byte[] replacement = "replacement bytes".getBytes(StandardCharsets.UTF_8);
        upload(TENANT, document.id(), "new.txt", MediaType.TEXT_PLAIN_VALUE, replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.deletedAt").value(nullValue()));

        assertThat(pointerRow(document.id()).get("deleted_at")).isNull();
        assertThat(pointerRow(document.id()).get("content")).isEqualTo(replacement);
        assertThat(versionRow(document.id(), 1).get("deleted_at")).isNotNull();
        assertThat(versionRow(document.id(), 1).get("content")).isNull();
        assertThat(versionRow(document.id(), 2).get("deleted_at")).isNull();
        assertThat(versionRow(document.id(), 2).get("content")).isEqualTo(replacement);

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(replacement));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONTENT_DELETED"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 2)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(replacement));
    }

    @Test
    void deleteAndEventRollBackTogether() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "still here".getBytes(StandardCharsets.UTF_8);
        upload(TENANT, document.id(), "rollback.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            contentService.deleteContent(TENANT, document.id());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(pointerRow(document.id()).get("content")).isEqualTo(bytes);
        assertThat(pointerRow(document.id()).get("deleted_at")).isNull();
        assertThat(countEvents(DELETE_EVENT)).isZero();
        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void schemaDdlIsIdempotentForContentTombstones() {
        rerunSchema();
        rerunSchema();

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_name IN ('document_content', 'document_content_version')
                   AND column_name = 'deleted_at'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable
                  FROM information_schema.columns
                 WHERE table_name = 'document_content' AND column_name = 'content'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable
                  FROM information_schema.columns
                 WHERE table_name = 'document_content_version' AND column_name = 'content'
                """, String.class)).isEqualTo("YES");
    }

    private void rerunSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"))
                .execute(dataSource);
    }

    private List<Map<String, Object>> events(UUID documentId, String eventType) {
        return jdbc.queryForList("""
                SELECT id, aggregate_type, aggregate_id, aggregate_version, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence
                """, documentId, eventType);
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

    private Document createDocument(UUID tenant) {
        return documentService.create(new CreateDocumentRequest(
                tenant, "Claims evidence", "POLICY", "CONFIDENTIAL", RETENTION));
    }

    /**
     * A legal hold may only be placed from ACTIVE, ARCHIVED, CLASSIFIED, or
     * SUPERSEDED; drive a fresh document through the lifecycle to ACTIVE.
     */
    private Document activeDocument() {
        Document document = createDocument(TENANT);
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("SCANNING", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("PROCESSING", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("CLASSIFIED", document.version()));
        return documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("ACTIVE", document.version()));
    }

    private ResultActions upload(UUID tenant, UUID documentId, String fileName,
                                 String contentType, byte[] bytes) throws Exception {
        MockHttpServletRequestBuilder request =
                put("/api/v1/documents/{id}/content", documentId)
                        .contentType(contentType)
                        .content(bytes);
        if (tenant != null) {
            request.header(TENANT_HEADER, tenant.toString());
        }
        if (fileName != null) {
            request.header(FILE_NAME_HEADER, URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replace("+", "%20"));
        }
        return mockMvc.perform(request);
    }

    private ResultActions deleteContent(UUID tenant, UUID documentId) throws Exception {
        MockHttpServletRequestBuilder request =
                delete("/api/v1/documents/{id}/content", documentId);
        if (tenant != null) {
            request.header(TENANT_HEADER, tenant.toString());
        }
        return mockMvc.perform(request);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?",
                Integer.class, eventType);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
