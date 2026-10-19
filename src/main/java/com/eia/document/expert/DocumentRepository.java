package com.eia.document.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit SQL over the {@code document} table. The expert owns persistence and
 * the optimistic {@code version} column; it does not decide business outcomes.
 */
@Repository
public class DocumentRepository {
    private static final String COLUMNS = """
            id, tenant_id, document_number, title, document_type, classification,
            status, retention_until, version, created_at, updated_at
            """;

    private static final RowMapper<Document> MAPPER = DocumentRepository::map;

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Document> findAll(UUID tenantId) {
        return jdbc.query("""
                SELECT %s
                  FROM document
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, document_number DESC
                """.formatted(COLUMNS), MAPPER, tenantId);
    }

    public Optional<Document> findById(UUID tenantId, UUID id) {
        return jdbc.query("""
                SELECT %s
                  FROM document
                 WHERE tenant_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, id).stream().findFirst();
    }

    public Document create(UUID tenantId, CreateDocumentRequest request, Instant now) {
        UUID id = UUID.randomUUID();
        String documentNumber = nextDocumentNumber(now);
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO document (id, tenant_id, document_number, title, document_type,
                                      classification, status, retention_until, version,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, tenantId, documentNumber, request.title(), request.documentType(),
                request.classification(), DocumentStatus.RECEIVED.name(), request.retentionUntil(),
                timestamp, timestamp);
        return findById(tenantId, id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    public Document updateStatus(Document current, DocumentStatus target, Instant now) {
        int updated = jdbc.update("""
                UPDATE document
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, target.name(), now.atOffset(ZoneOffset.UTC),
                current.tenantId(), current.id(), current.version());
        if (updated == 0) {
            throw new VersionConflictException("document " + current.id()
                    + " was changed concurrently; reload and retry");
        }
        return findById(current.tenantId(), current.id())
                .orElseThrow(() -> new DocumentNotFoundException(current.id()));
    }

    private String nextDocumentNumber(Instant now) {
        Long sequence = jdbc.queryForObject("SELECT nextval('document_number_seq')", Long.class);
        long value = sequence == null ? 0L : sequence;
        return "DOC-" + Year.of(now.atZone(ZoneOffset.UTC).getYear())
                + "-" + String.format("%07d", value);
    }

    private static Document map(ResultSet rs, int rowNum) throws SQLException {
        return new Document(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("document_number"),
                rs.getString("title"),
                rs.getString("document_type"),
                rs.getString("classification"),
                rs.getString("status"),
                rs.getObject("retention_until", LocalDate.class),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }
}
