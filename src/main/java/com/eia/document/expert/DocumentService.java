package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
    private static final String DEFAULT_DISPOSITION_REASON = "RETENTION_EXPIRED";

    private final DocumentRepository repository;
    private final DocumentLinkRepository linkRepository;
    private final DocumentContentRepository contentRepository;
    private final OutboxRepository outbox;

    public DocumentService(DocumentRepository repository,
                           DocumentLinkRepository linkRepository,
                           DocumentContentRepository contentRepository,
                           OutboxRepository outbox) {
        this.repository = repository;
        this.linkRepository = linkRepository;
        this.contentRepository = contentRepository;
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
     * Tenant-scoped read that keeps the document row locked for the rest of the
     * caller transaction. Content tombstoning uses it so a legal hold placed
     * concurrently either commits first (and blocks the delete) or waits until
     * the tombstone is committed.
     */
    @Transactional
    public Document getForUpdate(UUID tenantId, UUID id) {
        return repository.findByIdForUpdate(tenantId, id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
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
        outbox.insert(OutboxEvent.fromEnvelope(DocumentLinkEvents.added(document, link, now)));
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
        outbox.insert(OutboxEvent.fromEnvelope(DocumentLinkEvents.removed(document, link, Instant.now())));
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

    /**
     * Places a legal hold and records the previous status so a later release can
     * restore it. The document row and the {@code document.legal-hold.placed}
     * outbox fact commit in one transaction.
     */
    @Transactional
    public Document placeHold(UUID tenantId, UUID id, String reason) {
        Document current = get(tenantId, id);
        String holdReason = normalizeReason(reason);
        DocumentRules.validateHold(current, holdReason);
        Instant now = Instant.now();
        Document after = repository.placeHold(current, holdReason, now);
        outbox.insert(OutboxEvent.fromEnvelope(
                DocumentEvents.legalHoldPlaced(after, holdReason, now)));
        return after;
    }

    /**
     * Releases a legal hold, restoring the snapshotted status (falling back to
     * ARCHIVED when the snapshot is absent) and clearing the hold columns, all in
     * one transaction with the release fact.
     */
    @Transactional
    public Document releaseHold(UUID tenantId, UUID id) {
        Document current = get(tenantId, id);
        DocumentRules.validateRelease(current);
        Instant now = Instant.now();
        Document after = repository.releaseHold(current, DocumentRules.restoredStatus(current), now);
        outbox.insert(OutboxEvent.fromEnvelope(
                DocumentEvents.legalHoldReleased(after, current.holdReason(), now)));
        return after;
    }

    /**
     * Tombstones a document once its retention date has expired and no legal
     * hold applies. The disposition reason travels in the
     * {@code document.document.disposed} fact; the approval and certificate
     * steps from SPEC 11.6.8 are out of this slice.
     *
     * <p>Disposition also removes the stored content bytes. When the document
     * still has live content, its current pointer and every revision are
     * tombstoned in the same transaction and the matching
     * {@code document.content.deleted} fact, with the same payload as an
     * explicit content delete, is appended. A document without content, or
     * whose content is already tombstoned, is disposed with only
     * {@code document.document.disposed}.</p>
     */
    @Transactional
    public Document dispose(UUID tenantId, UUID id, String reason) {
        return dispose(tenantId, id, reason, null);
    }

    /**
     * Disposition variant used by the retention job. The actor id is carried by
     * the disposition and content-tombstone facts; {@code null} keeps the
     * default system attribution used by the manual API.
     */
    @Transactional
    public Document dispose(UUID tenantId, UUID id, String reason, UUID actorId) {
        Document current = get(tenantId, id);
        DocumentRules.validateDispose(current, LocalDate.now(ZoneOffset.UTC));
        String dispositionReason = normalizeDispositionReason(reason);
        Instant now = Instant.now();
        Document after = repository.dispose(current, now);
        outbox.insert(OutboxEvent.fromEnvelope(
                DocumentEvents.disposed(after, dispositionReason, now, actorId)));
        tombstoneContentForDisposal(after, now, actorId);
        return after;
    }

    /**
     * Tombstones the content that disposition must not leave behind. The
     * pointer row is locked while it is read, so a concurrent explicit content
     * delete either commits first (leaving nothing for this method to do) or
     * waits for the disposition to commit and then observes the tombstone.
     * Missing content and an existing tombstone are both no-ops: disposition
     * still succeeds with only its own fact.
     */
    private void tombstoneContentForDisposal(Document document, Instant now, UUID actorId) {
        Optional<DocumentContentMetadata> content = contentRepository
                .findMetadataForUpdate(document.tenantId(), document.id())
                .filter(metadata -> metadata.deletedAt() == null);
        if (content.isEmpty()) {
            return;
        }
        if (!contentRepository.tombstone(document.tenantId(), document.id(), now)) {
            return;
        }
        outbox.insert(OutboxEvent.fromEnvelope(
                DocumentContentEvents.deleted(document, content.get(), now, actorId)));
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

    private static String normalizeReason(String reason) {
        return reason == null ? null : reason.trim();
    }

    private static String normalizeDispositionReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_DISPOSITION_REASON;
        }
        return reason.trim();
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
