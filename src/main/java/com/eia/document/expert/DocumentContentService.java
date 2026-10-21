package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores and serves the current content of a document.
 *
 * <p>The document lookup goes through {@link DocumentService} so the tenant
 * wall is the same one used by every other document operation: a document
 * outside the tenant is indistinguishable from a missing document. Content
 * replacement is an upsert and each write appends its
 * {@code document.content.uploaded} fact to the transactional outbox in the
 * same transaction.</p>
 */
@Service
public class DocumentContentService {
    private final DocumentService documents;
    private final DocumentContentRepository repository;
    private final OutboxRepository outbox;

    public DocumentContentService(DocumentService documents,
                                  DocumentContentRepository repository,
                                  OutboxRepository outbox) {
        this.documents = documents;
        this.repository = repository;
        this.outbox = outbox;
    }

    @Transactional
    public DocumentContentMetadata upload(UUID tenantId, UUID documentId, String fileName,
                                          String contentType, byte[] content) {
        Document document = documents.get(tenantId, documentId);
        DocumentContentRules.validateSize(content);
        String safeFileName = DocumentContentRules.requireFileName(fileName);
        String safeContentType = DocumentContentRules.requireContentType(contentType);
        String sha256 = DocumentContentRules.sha256Hex(content);

        Instant now = Instant.now();
        DocumentContent stored = repository.save(
                tenantId, documentId, safeFileName, safeContentType, sha256, content, now);
        outbox.insert(OutboxEvent.fromEnvelope(DocumentContentEvents.uploaded(document, stored, now)));
        return stored.metadata();
    }

    /**
     * Returns the stored bytes for a download. A missing document is
     * {@code 404 NOT_FOUND}; a document without content is
     * {@code 404 CONTENT_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public DocumentContent getContent(UUID tenantId, UUID documentId) {
        documents.get(tenantId, documentId);
        return repository.findContent(tenantId, documentId)
                .orElseThrow(() -> new ContentNotFoundException(documentId));
    }

    @Transactional(readOnly = true)
    public DocumentContentMetadata getMetadata(UUID tenantId, UUID documentId) {
        documents.get(tenantId, documentId);
        return repository.findMetadata(tenantId, documentId)
                .orElseThrow(() -> new ContentNotFoundException(documentId));
    }
}
