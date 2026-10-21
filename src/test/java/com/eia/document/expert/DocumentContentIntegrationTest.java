package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the content routes against H2 in PostgreSQL mode:
 * raw upload and replacement, download headers, metadata, digest verification,
 * the size limits, the tenant wall, and the transactional outbox fact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentContentIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);
    private static final String TENANT_HEADER = "X-EIA-Tenant";
    private static final String FILE_NAME_HEADER = "X-File-Name";
    private static final String SHA_HEADER = "X-Content-SHA256";
    private static final String CONTENT_EVENT = "document.content.uploaded";

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
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void uploadStoresBytesComputesDigestAndWritesTheEvent() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "claim evidence".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);

        upload(TENANT, document.id(), "claim evidence.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.id().toString()))
                .andExpect(jsonPath("$.fileName").value("claim evidence.txt"))
                .andExpect(jsonPath("$.contentType").value(MediaType.TEXT_PLAIN_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.sha256").value(sha))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty())
                .andExpect(jsonPath("$.content").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM document_content WHERE document_id = ?", document.id());
        assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        assertThat(row.get("file_name")).isEqualTo("claim evidence.txt");
        assertThat(row.get("content_type")).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(row.get("sha256")).isEqualTo(sha);
        assertThat((byte[]) row.get("content")).isEqualTo(bytes);
        assertThat(row.get("uploaded_at")).isNotNull();
        assertThat(((Number) row.get("content_version")).longValue()).isEqualTo(1L);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT id, event_type, aggregate_type, aggregate_id, aggregate_version, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, document.id(), CONTENT_EVENT);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("aggregate_type")).isEqualTo("DocumentContent");
        assertThat(event.get("aggregate_id")).isEqualTo(document.id());
        assertThat(((Number) event.get("aggregate_version")).longValue()).isEqualTo(1L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"fileName\":\"claim evidence.txt\"")
                .contains("\"contentType\":\"text/plain\"")
                .contains("\"sizeBytes\":" + bytes.length)
                .contains("\"sha256\":\"" + sha + "\"");
        assertThat((String) event.get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"document\"");

        var envelope = outboxRepository.findById((UUID) event.get("id")).orElseThrow().toEnvelope();
        assertThat(envelope.eventType()).isEqualTo(CONTENT_EVENT);
        assertThat(envelope.data())
                .containsEntry("documentId", document.id().toString())
                .containsEntry("documentNumber", document.documentNumber())
                .containsEntry("fileName", "claim evidence.txt")
                .containsEntry("contentType", "text/plain")
                .containsEntry("sha256", sha);
        assertThat(((Number) envelope.data().get("sizeBytes")).longValue()).isEqualTo(bytes.length);
    }

    @Test
    void downloadReturnsTheBytesMimeTypeDispositionAndDigest() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "%PDF-1.7 evidence".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        upload(TENANT, document.id(), "report.pdf", MediaType.APPLICATION_PDF_VALUE, bytes)
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string(SHA_HEADER, sha))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"report.pdf\""))
                .andReturn();

        assertThat(result.getResponse().getContentLength()).isEqualTo(bytes.length);
    }

    @Test
    void metadataReturnsTheStoredMetadataAndContentNotFoundWhenAbsent() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "metadata body".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        upload(TENANT, document.id(), "manual.pdf", MediaType.APPLICATION_PDF_VALUE, bytes)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.id().toString()))
                .andExpect(jsonPath("$.fileName").value("manual.pdf"))
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.sha256").value(sha))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty())
                .andExpect(jsonPath("$.content").doesNotExist());

        jdbc.update("DELETE FROM document_content WHERE document_id = ?", document.id());
        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));
    }

    @Test
    void replaceKeepsOneRowAndWritesASecondEventAtTheNextVersion() throws Exception {
        Document document = createDocument(TENANT);
        byte[] first = "first revision".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second revision is longer".getBytes(StandardCharsets.UTF_8);

        upload(TENANT, document.id(), "first.txt", MediaType.TEXT_PLAIN_VALUE, first)
                .andExpect(status().isOk());
        upload(TENANT, document.id(), "second.pdf", MediaType.APPLICATION_PDF_VALUE, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("second.pdf"))
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(second.length))
                .andExpect(jsonPath("$.sha256").value(sha256(second)));

        assertThat(count("document_content")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM document_content WHERE document_id = ?", document.id());
        assertThat(row.get("file_name")).isEqualTo("second.pdf");
        assertThat((byte[]) row.get("content")).isEqualTo(second);
        assertThat(((Number) row.get("content_version")).longValue()).isEqualTo(2L);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence
                """, document.id(), CONTENT_EVENT);
        assertThat(events)
                .extracting(event -> ((Number) event.get("aggregate_version")).longValue())
                .containsExactly(1L, 2L);
        assertThat((String) events.getFirst().get("payload")).contains("\"fileName\":\"first.txt\"");
        assertThat((String) events.get(1).get("payload")).contains("\"fileName\":\"second.pdf\"");

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(second))
                .andExpect(header().string(SHA_HEADER, sha256(second)));
    }

    @Test
    void hashIsStableAcrossMetadataAndDownload() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        String expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

        upload(TENANT, document.id(), "hello.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(expected));

        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(expected));

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(SHA_HEADER, expected));
    }

    @Test
    void tooLargeContentIs413AndWritesNothing() throws Exception {
        Document document = createDocument(TENANT);
        byte[] tooLarge = new byte[DocumentContentRules.MAX_CONTENT_BYTES + 1];

        upload(TENANT, document.id(), "huge.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, tooLarge)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(containsString("maximum")));

        assertThat(count("document_content")).isZero();
        assertThat(countEvents(CONTENT_EVENT)).isZero();
    }

    @Test
    void emptyContentAndMissingFileNameAre400AndWriteNothing() throws Exception {
        Document document = createDocument(TENANT);

        upload(TENANT, document.id(), "empty.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0])
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_CONTENT"));

        upload(TENANT, document.id(), null, MediaType.APPLICATION_PDF_VALUE,
                "file".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_NAME_REQUIRED"));

        assertThat(count("document_content")).isZero();
        assertThat(countEvents(CONTENT_EVENT)).isZero();
    }

    @Test
    void tenantWallHidesContentForAnotherTenantAndAMissingTenant() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "tenant secret".getBytes(StandardCharsets.UTF_8);

        upload(OTHER_TENANT, document.id(), "secret.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        upload(null, document.id(), "secret.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        upload(TENANT, UUID.randomUUID(), "missing.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(count("document_content")).isZero();
        assertThat(countEvents(CONTENT_EVENT)).isZero();

        upload(TENANT, document.id(), "secret.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(count("document_content")).isEqualTo(1);
    }

    @Test
    void uploadAndEventRollBackTogether() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "rolled back".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            contentService.upload(TENANT, document.id(), "rollback.txt",
                    MediaType.TEXT_PLAIN_VALUE, bytes);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("document_content")).isZero();
        assertThat(countEvents(CONTENT_EVENT)).isZero();
    }

    @Test
    void deletingTheDocumentCascadesToItsContent() throws Exception {
        Document document = createDocument(TENANT);
        upload(TENANT, document.id(), "cascade.txt", MediaType.TEXT_PLAIN_VALUE,
                "cascade".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());

        assertThat(count("document_content")).isEqualTo(1);
        jdbc.update("DELETE FROM document WHERE id = ?", document.id());
        assertThat(count("document_content")).isZero();
    }

    @Test
    void uploadWithoutContentTypeDefaultsToOctetStream() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "raw bytes".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(put("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString())
                        .header(FILE_NAME_HEADER, "raw.bin")
                        .content(bytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_OCTET_STREAM_VALUE));
    }

    @Test
    void fileNamesAreUrlDecodedOnUpload() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "unicode name".getBytes(StandardCharsets.UTF_8);

        upload(TENANT, document.id(), "Q3 report €.pdf", MediaType.APPLICATION_PDF_VALUE, bytes)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("Q3 report €.pdf"));

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, allOf(
                        containsString("attachment"),
                        containsString("filename*=UTF-8''Q3%20report%20%E2%82%AC.pdf"))));
    }

    @Test
    void schemaDdlIsIdempotentForDocumentContent() {
        // Re-running the test schema must not fail on the second create.
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("schema-h2.sql"))
                .execute(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_name = 'document_content'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                 WHERE table_name = 'document_content'
                   AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isEqualTo(1);
    }

    private Document createDocument(UUID tenant) {
        return documentService.create(new CreateDocumentRequest(
                tenant, "Claims evidence", "POLICY", "CONFIDENTIAL", RETENTION));
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
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
