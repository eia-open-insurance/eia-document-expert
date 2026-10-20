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
 * Explicit SQL over {@code document_link}. Every query is tenant-scoped; the
 * unique key and the subject index mirror the DDL in {@code schema.sql}.
 */
@Repository
public class DocumentLinkRepository {
    private static final String COLUMNS = """
            id, document_id, linked_type, linked_id, linked_reference, created_at
            """;

    private static final RowMapper<DocumentLink> MAPPER = DocumentLinkRepository::map;

    private final JdbcTemplate jdbc;

    public DocumentLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DocumentLink> findAll(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                SELECT %s
                  FROM document_link
                 WHERE tenant_id = ? AND document_id = ?
                 ORDER BY created_at, id
                """.formatted(COLUMNS), MAPPER, tenantId, documentId);
    }

    public Optional<DocumentLink> findById(UUID tenantId, UUID documentId, UUID linkId) {
        return jdbc.query("""
                SELECT %s
                  FROM document_link
                 WHERE tenant_id = ? AND document_id = ? AND id = ?
                """.formatted(COLUMNS), MAPPER, tenantId, documentId, linkId).stream().findFirst();
    }

    public boolean exists(UUID tenantId, UUID documentId, String linkedType, UUID linkedId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM document_link
                 WHERE tenant_id = ? AND document_id = ? AND linked_type = ? AND linked_id = ?
                """, Integer.class, tenantId, documentId, linkedType, linkedId);
        return count != null && count > 0;
    }

    public void insert(UUID tenantId, DocumentLink link) {
        jdbc.update("""
                INSERT INTO document_link (
                    id, tenant_id, document_id, linked_type, linked_id,
                    linked_reference, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, link.id(), tenantId, link.documentId(), link.linkedType(), link.linkedId(),
                link.linkedReference(), link.createdAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * Removes exactly one link inside the tenant and document scope.
     *
     * @return true when the row existed and was deleted
     */
    public boolean delete(UUID tenantId, UUID documentId, UUID linkId) {
        return jdbc.update("""
                DELETE FROM document_link
                 WHERE tenant_id = ? AND document_id = ? AND id = ?
                """, tenantId, documentId, linkId) == 1;
    }

    private static DocumentLink map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        Instant instant = createdAt == null ? null : createdAt.toInstant();
        return new DocumentLink(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("linked_type"),
                rs.getObject("linked_id", UUID.class),
                rs.getString("linked_reference"),
                instant);
    }
}
