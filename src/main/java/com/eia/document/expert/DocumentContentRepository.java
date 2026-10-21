package com.eia.document.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit SQL over {@code document_content} and
 * {@code document_content_version}.
 *
 * <p>{@code document_content} stays the one-row current pointer: each upload
 * copies its metadata and bytes there and advances {@code current_version}.
 * The immutable revision rows are appended to
 * {@code document_content_version}, so a download of an older revision does not
 * depend on the pointer.</p>
 *
 * <p>Revision allocation locks the pointer row with {@code SELECT ... FOR
 * UPDATE}. The first upload inserts the pointer with {@code current_version =
 * 0} and the following update advances it to 1; a concurrent first upload
 * loses the {@code INSERT ... ON CONFLICT DO NOTHING} race and then reads the
 * winner's row under the lock. The version is one greater than both the
 * pointer and the highest stored revision, which also repairs a pointer that
 * lagged the history.</p>
 */
@Repository
public class DocumentContentRepository {
    private static final String METADATA_COLUMNS = """
            document_id, tenant_id, file_name, content_type, size_bytes, sha256,
            uploaded_at, current_version
            """;

    private static final String CONTENT_COLUMNS = METADATA_COLUMNS + ", content";

    private static final String VERSION_COLUMNS = """
            id, document_id, tenant_id, version, file_name, content_type,
            size_bytes, sha256, content, uploaded_at
            """;

    private static final String VERSION_METADATA_COLUMNS = """
            document_id, version, file_name, content_type, size_bytes, sha256,
            uploaded_at
            """;

    private static final RowMapper<DocumentContent> CONTENT_MAPPER =
            DocumentContentRepository::mapContent;
    private static final RowMapper<DocumentContentMetadata> METADATA_MAPPER =
            DocumentContentRepository::mapMetadata;
    private static final RowMapper<DocumentContentVersion> VERSION_MAPPER =
            DocumentContentRepository::mapVersion;
    private static final RowMapper<DocumentContentVersionMetadata> VERSION_METADATA_MAPPER =
            DocumentContentRepository::mapVersionMetadata;

    private static final String LOCK_POINTER_SQL = """
            SELECT current_version
              FROM document_content
             WHERE document_id = ? AND tenant_id = ?
             FOR UPDATE
            """;

    private static final String INSERT_POINTER_SQL = """
            INSERT INTO document_content (
                document_id, tenant_id, file_name, content_type, size_bytes, sha256,
                content, current_version, uploaded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String UPDATE_POINTER_SQL = """
            UPDATE document_content
               SET file_name = ?, content_type = ?, size_bytes = ?, sha256 = ?,
                   content = ?, current_version = ?, uploaded_at = ?
             WHERE document_id = ? AND tenant_id = ?
            """;

    private static final String INSERT_VERSION_SQL = """
            INSERT INTO document_content_version (
                id, document_id, tenant_id, version, file_name, content_type,
                size_bytes, sha256, content, uploaded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public DocumentContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends one immutable revision and advances the current pointer in the
     * same caller transaction. Returns the row as stored, including the
     * allocated {@code version}, which is also the event aggregate version.
     */
    public DocumentContentVersion appendVersion(UUID tenantId, UUID documentId, String fileName,
                                                String contentType, String sha256,
                                                byte[] content, Instant uploadedAt) {
        int size = content.length;
        OffsetDateTime timestamp = uploadedAt.atOffset(ZoneOffset.UTC);

        Integer currentVersion = lockPointer(tenantId, documentId);
        if (currentVersion == null) {
            jdbc.update(INSERT_POINTER_SQL, documentId, tenantId, fileName, contentType,
                    size, sha256, content, timestamp);
            // The row exists now: either this transaction inserted it or a
            // concurrent first upload won the conflict and committed first.
            currentVersion = lockPointer(tenantId, documentId);
            if (currentVersion == null) {
                throw new IllegalStateException("document content pointer for " + documentId
                        + " was not visible after insert");
            }
        }

        int nextVersion = Math.max(currentVersion, maxStoredVersion(documentId)) + 1;
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_VERSION_SQL, id, documentId, tenantId, nextVersion, fileName,
                contentType, size, sha256, content, timestamp);

        int updated = jdbc.update(UPDATE_POINTER_SQL, fileName, contentType, size, sha256,
                content, nextVersion, timestamp, documentId, tenantId);
        if (updated != 1) {
            throw new IllegalStateException("document content pointer for " + documentId
                    + " was not updated");
        }

        return new DocumentContentVersion(id, documentId, tenantId, nextVersion, fileName,
                contentType, size, sha256, content, uploadedAt);
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

    public Optional<DocumentContentVersion> findVersionContent(UUID tenantId, UUID documentId,
                                                               int version) {
        return jdbc.query("""
                SELECT %s
                  FROM document_content_version
                 WHERE document_id = ? AND tenant_id = ? AND version = ?
                """.formatted(VERSION_COLUMNS), VERSION_MAPPER, documentId, tenantId, version)
                .stream().findFirst();
    }

    public List<DocumentContentVersionMetadata> listVersions(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                SELECT %s
                  FROM document_content_version
                 WHERE document_id = ? AND tenant_id = ?
                 ORDER BY version DESC
                """.formatted(VERSION_METADATA_COLUMNS), VERSION_METADATA_MAPPER,
                documentId, tenantId);
    }

    /**
     * Locks and returns the pointer's current revision, or {@code null} when
     * the document has no content row yet.
     */
    private Integer lockPointer(UUID tenantId, UUID documentId) {
        return jdbc.query(LOCK_POINTER_SQL,
                        (ResultSet rs, int rowNum) -> rs.getInt("current_version"),
                        documentId, tenantId)
                .stream().findFirst().orElse(null);
    }

    private int maxStoredVersion(UUID documentId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0)
                  FROM document_content_version
                 WHERE document_id = ?
                """, Integer.class, documentId);
        return max == null ? 0 : max;
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
                rs.getInt("current_version")
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
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant(),
                rs.getInt("current_version")
        );
    }

    private static DocumentContentVersion mapVersion(ResultSet rs, int rowNum)
            throws SQLException {
        return new DocumentContentVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getInt("version"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getBytes("content"),
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant()
        );
    }

    private static DocumentContentVersionMetadata mapVersionMetadata(ResultSet rs, int rowNum)
            throws SQLException {
        return new DocumentContentVersionMetadata(
                rs.getObject("document_id", UUID.class),
                rs.getInt("version"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getObject("uploaded_at", OffsetDateTime.class).toInstant()
        );
    }
}
