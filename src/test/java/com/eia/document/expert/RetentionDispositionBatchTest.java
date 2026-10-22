package com.eia.document.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured batch size caps one run; the remaining due documents are
 * picked up by the next run.
 */
@SpringBootTest(properties = {
        "eia.document.retention.enabled=true",
        "eia.document.retention.batch-size=2"
})
@ActiveProfiles("test")
class RetentionDispositionBatchTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    private RetentionDispositionService retentionService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_content_version");
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void processesAtMostTheConfiguredBatchAndLeavesTheRestForTheNextRun() {
        Document oldest = activeDocument(TODAY.minusDays(3));
        Document middle = activeDocument(TODAY.minusDays(2));
        Document newest = activeDocument(TODAY.minusDays(1));

        RetentionDispositionService.RetentionDispositionSummary first =
                retentionService.runBatch();

        assertThat(first).isEqualTo(
                new RetentionDispositionService.RetentionDispositionSummary(2, 2, 0));
        assertThat(statusOf(oldest.id())).isEqualTo("DISPOSED");
        assertThat(statusOf(middle.id())).isEqualTo("DISPOSED");
        assertThat(statusOf(newest.id())).isEqualTo("ACTIVE");

        RetentionDispositionService.RetentionDispositionSummary second =
                retentionService.runBatch();

        assertThat(second).isEqualTo(
                new RetentionDispositionService.RetentionDispositionSummary(1, 1, 0));
        assertThat(statusOf(newest.id())).isEqualTo("DISPOSED");
    }

    private Document activeDocument(LocalDate retentionUntil) {
        Document document = documentService.create(new CreateDocumentRequest(
                TENANT, "Batch retention evidence", "POLICY", "CONFIDENTIAL", TODAY));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("SCANNING", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("PROCESSING", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("CLASSIFIED", document.version()));
        document = documentService.changeStatus(TENANT, document.id(),
                new UpdateDocumentStatusRequest("ACTIVE", document.version()));
        jdbc.update("UPDATE document SET retention_until = ? WHERE id = ?",
                retentionUntil, document.id());
        return document;
    }

    private String statusOf(UUID documentId) {
        return jdbc.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, documentId);
    }
}
