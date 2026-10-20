package com.eia.document.expert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the link table and the link/search SQL against the real
 * PostgreSQL database. Enabled only when {@code EIA_TEST_DB=true}.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EIA_TEST_DB", matches = "true")
class DocumentLinkRepositoryPostgresTest {
    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentLinkRepository linkRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void roundTripsLinkAndServesFilterAndReferenceSearch() {
        UUID tenant = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        LocalDate retention = LocalDate.now(ZoneOffset.UTC).plusYears(10);
        try {
            Document document = documentRepository.create(tenant, new CreateDocumentRequest(tenant,
                    "Postgres link round trip", "POLICY", "CONFIDENTIAL", retention), Instant.now());
            DocumentLink link = new DocumentLink(UUID.randomUUID(), document.id(), "DIRECT_CLAIM",
                    target, "CLM-2026-900001", Instant.now());
            linkRepository.insert(tenant, link);

            assertThat(linkRepository.findAll(tenant, document.id()))
                    .extracting(DocumentLink::id)
                    .containsExactly(link.id());
            assertThat(linkRepository.exists(tenant, document.id(), "DIRECT_CLAIM", target)).isTrue();
            assertThat(linkRepository.findById(tenant, document.id(), link.id()))
                    .hasValueSatisfying(found -> assertThat(found.linkedReference())
                            .isEqualTo("CLM-2026-900001"));

            assertThat(documentRepository.findAll(tenant, "DIRECT_CLAIM", target, null))
                    .extracting(Document::id)
                    .containsExactly(document.id());
            assertThat(documentRepository.findAll(tenant, null, null, "clm-2026-900001"))
                    .extracting(Document::id)
                    .containsExactly(document.id());

            assertThat(linkRepository.delete(tenant, document.id(), link.id())).isTrue();
            assertThat(linkRepository.findAll(tenant, document.id())).isEmpty();
        } finally {
            jdbc.update("DELETE FROM document_link WHERE tenant_id = ?", tenant);
            jdbc.update("DELETE FROM document WHERE tenant_id = ?", tenant);
        }
    }
}
