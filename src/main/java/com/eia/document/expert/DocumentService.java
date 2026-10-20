package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Applies the document invariants before and after persistence. The service is
 * the only entry point the internal and public APIs use.
 *
 * <p>Every aggregate write also appends its domain event to the transactional
 * outbox in the same transaction (SPEC 7.5.1), so a rollback leaves neither the
 * document mutation nor the event behind.</p>
 */
@Service
public class DocumentService {
    private static final String SEARCH_TYPE = "DOCUMENT";
    private static final int MAX_SEARCH_LIMIT = 25;
    private static final String FIELD_SEPARATOR = " · ";

    private final DocumentRepository repository;
    private final DocumentLinkRepository linkRepository;
    private final OutboxRepository outbox;

    public DocumentService(DocumentRepository repository,
                           DocumentLinkRepository linkRepository,
                           OutboxRepository outbox) {
        this.repository = repository;
        this.linkRepository = linkRepository;
        this.outbox = outbox;
    }

    public List<Document> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    /**
     * Tenant-scoped list with the optional subject filter and free-text term.
     * The term is served by the repository so a link reference match is found
     * without loading every document first.
     */
    public List<Document> list(UUID tenantId, String linkedType, UUID linkedId, String q) {
        DocumentRules.validateLinkFilter(linkedType, linkedId);
        String type = linkedType == null ? null : DocumentRules.requireLinkedType(linkedType).name();
        String query = normalizeQuery(q);
        if (type == null && query == null) {
            return repository.findAll(tenantId);
        }
        return repository.findAll(tenantId, type, linkedId, query);
    }

    public Document get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * Search projection used by the shell and EntityPicker. The response shape
     * is the shared {@code {query, limit, items}} envelope; each item exposes
     * {@code id}, {@code label}, {@code secondary}, {@code status}, and
     * {@code href}. The repository matches document metadata and link
     * references.
     */
    public DocumentSearchResponse search(UUID tenantId, String q, int limit) {
        String query = normalizeQuery(q);
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        List<Document> documents = repository.findAll(tenantId, null, null, query);
        List<DocumentSearchItem> items = documents.stream()
                .sorted(Comparator.comparing(Document::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(effectiveLimit)
                .map(DocumentService::toSearchItem)
                .toList();
        return new DocumentSearchResponse(query == null ? "" : query, effectiveLimit, items);
    }

    public List<DocumentLinkDto> listLinks(UUID tenantId, UUID documentId) {
        get(tenantId, documentId);
        return linkRepository.findAll(tenantId, documentId).stream()
                .map(DocumentLinkDto::from)
                .toList();
    }

    /**
     * Adds one association and its outbox fact atomically. The unique key is
     * the final duplicate guard; the pre-check keeps the common duplicate off
     * the database error path.
     */
    @Transactional
    public DocumentLinkDto addLink(UUID tenantId, UUID documentId, CreateDocumentLinkRequest request) {
        Document document = get(tenantId, documentId);
        LinkedType linkedType = DocumentRules.requireLinkedType(request.linkedType());
        if (linkRepository.exists(tenantId, documentId, linkedType.name(), request.linkedId())) {
            throw new DuplicateLinkException(documentId, linkedType.name(), request.linkedId());
        }

        Instant now = Instant.now();
        DocumentLink link = new DocumentLink(
                UUID.randomUUID(),
                documentId,
                linkedType.name(),
                request.linkedId(),
                normalizeReference(request.linkedReference()),
                now);
        try {
            linkRepository.insert(tenantId, link);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateLinkException(documentId, linkedType.name(), request.linkedId());
        }
        outbox.insert(DocumentLinkEvents.added(document, link, now));
        return DocumentLinkDto.from(link);
    }

    /**
     * Removes one association and writes the removal fact in the same
     * transaction. The link must be visible under both the tenant and the
     * document, so another document's link id is indistinguishable from a
     * missing link.
     */
    @Transactional
    public void removeLink(UUID tenantId, UUID documentId, UUID linkId) {
        Document document = get(tenantId, documentId);
        DocumentLink link = linkRepository.findById(tenantId, documentId, linkId)
                .orElseThrow(() -> new DocumentLinkNotFoundException(linkId));
        if (!linkRepository.delete(tenantId, documentId, linkId)) {
            throw new DocumentLinkNotFoundException(linkId);
        }
        outbox.insert(DocumentLinkEvents.removed(document, link, Instant.now()));
    }

    @Transactional
    public Document create(CreateDocumentRequest request) {
        Instant now = Instant.now();
        DocumentRules.validateCreate(request, now);
        Document document = repository.create(request.tenantId(), request, now);
        outbox.insert(OutboxEvent.fromEnvelope(DocumentEvents.created(document, now)));
        return document;
    }

    @Transactional
    public Document changeStatus(UUID tenantId, UUID id, UpdateDocumentStatusRequest request) {
        Document current = get(tenantId, id);
        DocumentRules.validateTransition(current, request.status(), request.expectedVersion());
        Instant now = Instant.now();
        Document after = repository.updateStatus(current, DocumentStatus.of(request.status()), now);
        outbox.insert(OutboxEvent.fromEnvelope(DocumentEvents.updated(current, after, now)));
        outbox.insert(OutboxEvent.fromEnvelope(DocumentEvents.statusChanged(current, after, now)));
        return after;
    }

    private static DocumentSearchItem toSearchItem(Document document) {
        String primary = isBlank(document.title()) ? document.documentType() : document.title();
        return new DocumentSearchItem(
                document.id(),
                SEARCH_TYPE,
                document.documentNumber(),
                secondary(primary, document.status()),
                document.status(),
                "/document?record=" + document.id());
    }

    private static String secondary(String primary, String status) {
        if (isBlank(primary)) {
            return status;
        }
        if (isBlank(status)) {
            return primary;
        }
        return primary + FIELD_SEPARATOR + status;
    }

    private static String normalizeReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return reference.trim();
    }

    private static String normalizeQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return q.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
