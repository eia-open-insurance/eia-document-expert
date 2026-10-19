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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Round-trips the repository against the real PostgreSQL {@code eia_document}
 * database. Enabled only when {@code EIA_TEST_DB=true} so the default build does
 * not require a running database; the live smoke test in the delivery notes
 * covers the same path against the started service.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EIA_TEST_DB", matches = "true")
class DocumentRepositoryPostgresTest {
    @Autowired
    private DocumentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void roundTripsDocumentThroughPostgreSql() {
        UUID tenant = UUID.randomUUID();
        LocalDate retention = LocalDate.now(ZoneOffset.UTC).plusYears(10);
        try {
            Document created = repository.create(tenant, new CreateDocumentRequest(tenant,
                    "Postgres round trip", "POLICY", "CONFIDENTIAL", retention), Instant.now());

            assertThat(created.documentNumber()).matches("DOC-\\d{4}-\\d{7}");
            assertThat(created.status()).isEqualTo("RECEIVED");
            assertThat(created.version()).isZero();
            assertThat(created.retentionUntil()).isEqualTo(retention);
            assertThat(created.createdAt()).isNotNull();

            assertThat(repository.findById(tenant, created.id()))
                    .hasValueSatisfying(found -> {
                        assertThat(found.documentNumber()).isEqualTo(created.documentNumber());
                        assertThat(found.title()).isEqualTo("Postgres round trip");
                        assertThat(found.documentType()).isEqualTo("POLICY");
                        assertThat(found.classification()).isEqualTo("CONFIDENTIAL");
                    });
            assertThat(repository.findAll(tenant)).hasSize(1);

            Document advanced = repository.updateStatus(created, DocumentStatus.SCANNING, Instant.now());
            assertThat(advanced.status()).isEqualTo("SCANNING");
            assertThat(advanced.version()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM document WHERE tenant_id = ?", tenant);
        }
    }

    @Test
    void rejectsStaleStatusUpdateThroughOptimisticLock() {
        UUID tenant = UUID.randomUUID();
        try {
            Document created = repository.create(tenant, new CreateDocumentRequest(tenant,
                    "Optimistic lock", "FNOL", "INTERNAL", null), Instant.now());
            Document once = repository.updateStatus(created, DocumentStatus.SCANNING, Instant.now());

            assertThat(once.version()).isEqualTo(1);
            VersionConflictException conflict = catchThrowableOfType(
                    () -> repository.updateStatus(created, DocumentStatus.SCANNING, Instant.now()),
                    VersionConflictException.class);
            assertThat(conflict).isNotNull();
        } finally {
            jdbc.update("DELETE FROM document WHERE tenant_id = ?", tenant);
        }
    }
}
