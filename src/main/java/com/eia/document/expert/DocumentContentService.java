package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stores and serves the content of a document as an append-only revision
 * history.
 *
 * <p>The document lookup goes through {@link DocumentService} so the tenant
 * wall is the same one used by every other document operation: a document
 * outside the tenant is indistinguishable from a missing document. Every
 * upload appends a new immutable revision, refreshes the
 * {@code document_content} current pointer, and writes its
 * {@code document.content.uploaded} fact to the transactional outbox in the
 * same transaction. Downloads of the current revision read the pointer;
 * downloads of an older revision read the matching history row.</p>
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
        DocumentContentVersion stored = repository.appendVersion(
                tenantId, documentId, safeFileName, safeContentType, sha256, content, now);
        DocumentContentMetadata current = repository.findMetadata(tenantId, documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "document content metadata for " + documentId
                                + " was not visible after save"));
        outbox.insert(OutboxEvent.fromEnvelope(DocumentContentEvents.uploaded(document, stored, now)));
        return current;
    }

    /**
     * Returns the bytes of the current revision. A missing document is
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

    /**
     * Lists revision metadata newest first. A document with no content simply
     * has no revisions, so the result is empty rather than a 404.
     */
    @Transactional(readOnly = true)
    public List<DocumentContentVersionMetadata> listVersions(UUID tenantId, UUID documentId) {
        documents.get(tenantId, documentId);
        return repository.listVersions(tenantId, documentId);
    }

    /**
     * Returns the stored bytes of one revision. The document must be visible
     * under the tenant; an unknown revision is
     * {@code 404 CONTENT_VERSION_NOT_FOUND}.
     */
    @Transactional(readOnly = true)
    public DocumentContentVersion getVersionContent(UUID tenantId, UUID documentId, int version) {
        documents.get(tenantId, documentId);
        if (version < 1) {
            throw new ContentVersionNotFoundException(documentId, version);
        }
        return repository.findVersionContent(tenantId, documentId, version)
                .orElseThrow(() -> new ContentVersionNotFoundException(documentId, version));
    }
}
