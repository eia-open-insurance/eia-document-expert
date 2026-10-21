package com.eia.document.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit SQL over {@code document_content}.
 *
 * <p>One row exists per document. Saving first tries the tenant-scoped update
 * and falls back to {@code INSERT ... ON CONFLICT DO NOTHING}; when a
 * concurrent first upload wins the insert race, the loser applies its update
 * to the winner's row. {@code DO NOTHING} is supported by both PostgreSQL and
 * the H2 test database, and avoids aborting the surrounding transaction the
 * way an unhandled duplicate-key error would on PostgreSQL.</p>
 */
@Repository
public class DocumentContentRepository {
    private static final String METADATA_COLUMNS = """
            document_id, tenant_id, file_name, content_type, size_bytes, sha256,
            uploaded_at, content_version
            """;

    private static final String CONTENT_COLUMNS = METADATA_COLUMNS + ", content";

    private static final RowMapper<DocumentContent> CONTENT_MAPPER =
            DocumentContentRepository::mapContent;
    private static final RowMapper<DocumentContentMetadata> METADATA_MAPPER =
            DocumentContentRepository::mapMetadata;

    private static final String UPDATE_SQL = """
            UPDATE document_content
               SET file_name = ?, content_type = ?, size_bytes = ?, sha256 = ?,
                   content = ?, uploaded_at = ?, content_version = content_version + 1
             WHERE document_id = ? AND tenant_id = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO document_content (
                document_id, tenant_id, file_name, content_type, size_bytes, sha256,
                content, content_version, uploaded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public DocumentContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the first content row or replaces the current one. The stored
     * {@code content_version} increments on replacement so the outbox event
     * for this write gets a fresh aggregate version.
     */
    public DocumentContent save(UUID tenantId, UUID documentId, String fileName,
                                String contentType, String sha256, byte[] content,
                                Instant uploadedAt) {
        int size = content.length;
        OffsetDateTime timestamp = uploadedAt.atOffset(ZoneOffset.UTC);

        int updated = jdbc.update(UPDATE_SQL, fileName, contentType, size, sha256,
                content, timestamp, documentId, tenantId);
        if (updated == 0) {
            int inserted = jdbc.update(INSERT_SQL, documentId, tenantId, fileName, contentType,
                    size, sha256, content, timestamp);
            if (inserted == 0) {
                // A concurrent first upload inserted the row between our update
                // and insert; replace that row instead of failing the request.
                jdbc.update(UPDATE_SQL, fileName, contentType, size, sha256,
                        content, timestamp, documentId, tenantId);
            }
        }

        return findContent(tenantId, documentId).orElseThrow(() -> new IllegalStateException(
                "document content for " + documentId + " was not visible after save"));
    }

    public Optional<DocumentContent> findContent(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                SELECT %s
                  FROM document_content
                 WHERE document_id = ? AND tenant_id = ?
                """.formatted(CONTENT_COLUMNS), CONTENT_MAPPER, documentId, tenantId)
                .stream().findFirst();
    }

    public Optional<DocumentContentMetadata> findMetadata(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                SELECT %s
                  FROM document_content
                 WHERE document_id = ? AND tenant_id = ?
                """.formatted(METADATA_COLUMNS), METADATA_MAPPER, documentId, tenantId)
                .stream().findFirst();
    }

    private static DocumentContent mapContent(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentContent(
                rs.getObject("document_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getBytes("content"),
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                rs.getLong("content_version")
        );
    }

    private static DocumentContentMetadata mapMetadata(ResultSet rs, int rowNum)
            throws SQLException {
        return new DocumentContentMetadata(
                rs.getObject("document_id", UUID.class),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant()
        );
    }
}
