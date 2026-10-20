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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        return findAll(tenantId, null, null, null);
    }

    /**
     * Tenant-scoped list with the link filter used by the public document list
     * and the free-text term used by search. {@code q} matches the document
     * number, title, and type, and also the reference snapshot of any link, so
     * a user can find a document by the claim or policy number it is filed
     * against.
     *
     * <p>{@code linkedType} and {@code linkedId} are supplied together; the
     * service rejects a partial pair before reaching this method.</p>
     */
    public List<Document> findAll(UUID tenantId, String linkedType, UUID linkedId, String q) {
        StringBuilder sql = new StringBuilder("""
                SELECT %s
                  FROM document
                 WHERE tenant_id = ?
                """.formatted(COLUMNS));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);

        if (linkedType != null) {
            sql.append("""
                      AND EXISTS (
                            SELECT 1
                              FROM document_link l
                             WHERE l.tenant_id = document.tenant_id
                               AND l.document_id = document.id
                               AND l.linked_type = ?
                               AND l.linked_id = ?
                          )
                    """);
            args.add(linkedType);
            args.add(linkedId);
        }

        if (q != null) {
            String pattern = likePattern(q);
            sql.append("""
                      AND (
                            LOWER(document_number) LIKE ? ESCAPE '!'
                         OR LOWER(title) LIKE ? ESCAPE '!'
                         OR LOWER(document_type) LIKE ? ESCAPE '!'
                         OR EXISTS (
                                SELECT 1
                                  FROM document_link l
                                 WHERE l.tenant_id = document.tenant_id
                                   AND l.document_id = document.id
                                   AND LOWER(l.linked_reference) LIKE ? ESCAPE '!'
                            )
                          )
                    """);
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        sql.append(" ORDER BY created_at DESC, document_number DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
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

    private static String likePattern(String raw) {
        String needle = raw.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + needle + "%";
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
