package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies the document invariants before and after persistence. The service is
 * the only entry point the internal API uses.
 *
 * <p>Every aggregate write also appends its domain event to the transactional
 * outbox in the same transaction (SPEC 7.5.1), so a rollback leaves neither the
 * document mutation nor the event behind.</p>
 */
@Service
public class DocumentService {
    private final DocumentRepository repository;
    private final OutboxRepository outbox;

    public DocumentService(DocumentRepository repository, OutboxRepository outbox) {
        this.repository = repository;
        this.outbox = outbox;
    }

    public List<Document> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Document get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new DocumentNotFoundException(id));
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
}
