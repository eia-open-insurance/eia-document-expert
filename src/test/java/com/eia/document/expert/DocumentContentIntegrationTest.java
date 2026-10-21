package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * raw upload and replacement, the append-only revision history, download of
 * the current and older revisions, metadata, digest verification, the size
 * limits, the tenant wall, the legacy-content backfill, and the transactional
 * outbox facts.
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
    private static final String VERSION_HEADER = "X-Content-Version";
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
        jdbc.update("DELETE FROM document_content_version");
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void uploadStoresBytesComputesDigestAndWritesVersionOne() throws Exception {
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
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.content").doesNotExist());

        Map<String, Object> pointer = jdbc.queryForMap(
                "SELECT * FROM document_content WHERE document_id = ?", document.id());
        assertThat(pointer.get("tenant_id")).isEqualTo(TENANT);
        assertThat(pointer.get("file_name")).isEqualTo("claim evidence.txt");
        assertThat(pointer.get("content_type")).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
        assertThat(((Number) pointer.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(pointer.get("sha256")).isEqualTo(sha);
        assertThat((byte[]) pointer.get("content")).isEqualTo(bytes);
        assertThat(pointer.get("uploaded_at")).isNotNull();
        assertThat(((Number) pointer.get("current_version")).intValue()).isEqualTo(1);

        Map<String, Object> version = versionRow(document.id(), 1);
        assertThat(version.get("id")).isNotNull();
        assertThat(version.get("tenant_id")).isEqualTo(TENANT);
        assertThat(version.get("file_name")).isEqualTo("claim evidence.txt");
        assertThat(version.get("content_type")).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
        assertThat(((Number) version.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(version.get("sha256")).isEqualTo(sha);
        assertThat((byte[]) version.get("content")).isEqualTo(bytes);
        assertThat(version.get("uploaded_at")).isNotNull();

        List<Map<String, Object>> events = contentEvents(document.id());
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("aggregate_type")).isEqualTo("DocumentContent");
        assertThat(event.get("aggregate_id")).isEqualTo(document.id());
        assertThat(((Number) event.get("aggregate_version")).intValue()).isEqualTo(1);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"version\":1")
                .contains("\"fileName\":\"claim evidence.txt\"")
                .contains("\"contentType\":\"text/plain\"")
                .contains("\"sizeBytes\":" + bytes.length)
                .contains("\"sha256\":\"" + sha + "\"");
        assertThat((String) event.get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"document\"");

        var envelope = outboxRepository.findById((UUID) event.get("id")).orElseThrow().toEnvelope();
        assertThat(envelope.eventType()).isEqualTo(CONTENT_EVENT);
        assertThat(envelope.aggregateVersion()).isEqualTo(1);
        assertThat(envelope.data())
                .containsEntry("documentId", document.id().toString())
                .containsEntry("documentNumber", document.documentNumber())
                .containsEntry("fileName", "claim evidence.txt")
                .containsEntry("contentType", "text/plain")
                .containsEntry("sha256", sha);
        assertThat(((Number) envelope.data().get("sizeBytes")).longValue()).isEqualTo(bytes.length);
        assertThat(((Number) envelope.data().get("version")).intValue()).isEqualTo(1);
    }

    @Test
    void downloadReturnsTheCurrentBytesMimeTypeDispositionDigestAndVersion() throws Exception {
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
                .andExpect(header().string(VERSION_HEADER, "1"))
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
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.content").doesNotExist());

        jdbc.update("DELETE FROM document_content_version WHERE document_id = ?", document.id());
        jdbc.update("DELETE FROM document_content WHERE document_id = ?", document.id());
        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void replacementAppendsVersionTwoAndKeepsVersionOneDownloadable() throws Exception {
        Document document = createDocument(TENANT);
        byte[] first = "first revision".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second revision is longer".getBytes(StandardCharsets.UTF_8);

        upload(TENANT, document.id(), "first.txt", MediaType.TEXT_PLAIN_VALUE, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1));
        upload(TENANT, document.id(), "second.pdf", MediaType.APPLICATION_PDF_VALUE, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("second.pdf"))
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$.sizeBytes").value(second.length))
                .andExpect(jsonPath("$.sha256").value(sha256(second)))
                .andExpect(jsonPath("$.currentVersion").value(2));

        assertThat(count("document_content")).isEqualTo(1);
        Map<String, Object> pointer = jdbc.queryForMap(
                "SELECT * FROM document_content WHERE document_id = ?", document.id());
        assertThat(pointer.get("file_name")).isEqualTo("second.pdf");
        assertThat((byte[]) pointer.get("content")).isEqualTo(second);
        assertThat(((Number) pointer.get("current_version")).intValue()).isEqualTo(2);
        assertThat(count("document_content_version")).isEqualTo(2);

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].documentId").value(document.id().toString()))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].fileName").value("second.pdf"))
                .andExpect(jsonPath("$[0].contentType").value(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(jsonPath("$[0].sizeBytes").value(second.length))
                .andExpect(jsonPath("$[0].sha256").value(sha256(second)))
                .andExpect(jsonPath("$[0].uploadedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].content").doesNotExist())
                .andExpect(jsonPath("$[1].documentId").value(document.id().toString()))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].fileName").value("first.txt"))
                .andExpect(jsonPath("$[1].contentType").value(MediaType.TEXT_PLAIN_VALUE))
                .andExpect(jsonPath("$[1].sizeBytes").value(first.length))
                .andExpect(jsonPath("$[1].sha256").value(sha256(first)))
                .andExpect(jsonPath("$[1].uploadedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes(first))
                .andExpect(header().string(SHA_HEADER, sha256(first)))
                .andExpect(header().string(VERSION_HEADER, "1"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"first.txt\""));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 2)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(second))
                .andExpect(header().string(SHA_HEADER, sha256(second)))
                .andExpect(header().string(VERSION_HEADER, "2"));

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(second))
                .andExpect(header().string(SHA_HEADER, sha256(second)))
                .andExpect(header().string(VERSION_HEADER, "2"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.fileName").value("second.pdf"));

        List<Map<String, Object>> events = contentEvents(document.id());
        assertThat(events)
                .extracting(event -> ((Number) event.get("aggregate_version")).intValue())
                .containsExactly(1, 2);
        assertThat((String) events.getFirst().get("payload"))
                .contains("\"version\":1")
                .contains("\"fileName\":\"first.txt\"");
        assertThat((String) events.get(1).get("payload"))
                .contains("\"version\":2")
                .contains("\"fileName\":\"second.pdf\"");
        var envelope = outboxRepository.findById((UUID) events.get(1).get("id"))
                .orElseThrow().toEnvelope();
        assertThat(envelope.aggregateVersion()).isEqualTo(2);
        assertThat(((Number) envelope.data().get("version")).intValue()).isEqualTo(2);
    }

    @Test
    void hashIsStableAcrossVersionsMetadataAndDownloads() throws Exception {
        Document document = createDocument(TENANT);
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        String helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

        upload(TENANT, document.id(), "hello.txt", MediaType.TEXT_PLAIN_VALUE, hello)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(helloSha))
                .andExpect(jsonPath("$.currentVersion").value(1));

        byte[] later = "later revision".getBytes(StandardCharsets.UTF_8);
        upload(TENANT, document.id(), "later.txt", MediaType.TEXT_PLAIN_VALUE, later)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2));

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sha256").value(sha256(later)))
                .andExpect(jsonPath("$[1].sha256").value(helloSha));

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(hello))
                .andExpect(header().string(SHA_HEADER, helloSha));

        mockMvc.perform(get("/api/v1/documents/{id}/content/metadata", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(sha256(later)))
                .andExpect(jsonPath("$.currentVersion").value(2));

        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(SHA_HEADER, sha256(later)))
                .andExpect(header().string(VERSION_HEADER, "2"));
    }

    @Test
    void versionListAndHistoricalDownloadAreTenantScoped() throws Exception {
        Document document = createDocument(TENANT);
        byte[] bytes = "tenant secret".getBytes(StandardCharsets.UTF_8);
        upload(TENANT, document.id(), "secret.txt", MediaType.TEXT_PLAIN_VALUE, bytes)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .header(TENANT_HEADER, OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", UUID.randomUUID())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                UUID.randomUUID(), 1)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentId").value(document.id().toString()))
                .andExpect(jsonPath("$[0].version").value(1));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void unknownContentVersionIs404() throws Exception {
        Document document = createDocument(TENANT);
        upload(TENANT, document.id(), "only.txt", MediaType.TEXT_PLAIN_VALUE,
                "only revision".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 99)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_VERSION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 0)
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTENT_VERSION_NOT_FOUND"));
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
        assertThat(count("document_content_version")).isZero();
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
        assertThat(count("document_content_version")).isZero();
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
        assertThat(count("document_content_version")).isZero();
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
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions/{version}",
                                document.id(), 1)
                        .header(TENANT_HEADER, OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{id}/content", document.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(count("document_content")).isEqualTo(1);
        assertThat(count("document_content_version")).isEqualTo(1);
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
        assertThat(count("document_content_version")).isZero();
        assertThat(countEvents(CONTENT_EVENT)).isZero();
    }

    @Test
    void deletingTheDocumentCascadesToItsContentAndVersions() throws Exception {
        Document document = createDocument(TENANT);
        upload(TENANT, document.id(), "cascade.txt", MediaType.TEXT_PLAIN_VALUE,
                "cascade".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());
        upload(TENANT, document.id(), "cascade-v2.txt", MediaType.TEXT_PLAIN_VALUE,
                "cascade again".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk());

        assertThat(count("document_content")).isEqualTo(1);
        assertThat(count("document_content_version")).isEqualTo(2);
        jdbc.update("DELETE FROM document WHERE id = ?", document.id());
        assertThat(count("document_content")).isZero();
        assertThat(count("document_content_version")).isZero();
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
                .andExpect(jsonPath("$.contentType").value(MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(jsonPath("$.currentVersion").value(1));

        Map<String, Object> version = versionRow(document.id(), 1);
        assertThat(version.get("content_type"))
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
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

        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("Q3 report €.pdf"));
    }

    @Test
    void schemaBackfillsVersionOneForContentThatPredatesVersioning() throws Exception {
        Document document = createDocument(TENANT);
        byte[] legacy = "legacy bytes".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(legacy);
        jdbc.update("""
                INSERT INTO document_content (
                    document_id, tenant_id, file_name, content_type, size_bytes, sha256,
                    content, uploaded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, document.id(), TENANT, "legacy.pdf", MediaType.APPLICATION_PDF_VALUE,
                legacy.length, sha, legacy, Instant.now().atOffset(ZoneOffset.UTC));

        assertThat(count("document_content_version")).isZero();

        rerunSchema();

        assertThat(count("document_content_version")).isEqualTo(1);
        Map<String, Object> imported = versionRow(document.id(), 1);
        assertThat(imported.get("tenant_id")).isEqualTo(TENANT);
        assertThat(imported.get("file_name")).isEqualTo("legacy.pdf");
        assertThat(imported.get("content_type")).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(imported.get("sha256")).isEqualTo(sha);
        assertThat((byte[]) imported.get("content")).isEqualTo(legacy);

        rerunSchema();
        assertThat(count("document_content_version")).isEqualTo(1);

        upload(TENANT, document.id(), "current.pdf", MediaType.APPLICATION_PDF_VALUE,
                "new bytes".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2));
        mockMvc.perform(get("/api/v1/documents/{id}/content/versions", document.id())
                        .header(TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].documentId").value(document.id().toString()))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].documentId").value(document.id().toString()))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].fileName").value("legacy.pdf"))
                .andExpect(jsonPath("$[1].sha256").value(sha));
    }

    @Test
    void schemaDdlIsIdempotentForContentVersioning() {
        // Re-running the test schema must not fail on any create, alter, or
        // backfill statement.
        rerunSchema();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_name IN ('document_content', 'document_content_version')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                 WHERE table_name = 'document_content'
                   AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                 WHERE table_name = 'document_content_version'
                   AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                 WHERE table_name = 'document_content_version'
                   AND constraint_type = 'UNIQUE'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.indexes
                 WHERE table_name = 'document_content_version'
                   AND index_name = 'idx_document_content_version_document'
                """, Integer.class)).isEqualTo(1);
    }

    private void rerunSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql"))
                .execute(jdbc.getDataSource());
    }

    private List<Map<String, Object>> contentEvents(UUID documentId) {
        return jdbc.queryForList("""
                SELECT id, aggregate_type, aggregate_id, aggregate_version, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence
                """, documentId, CONTENT_EVENT);
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
