package com.eia.document.expert;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies the document invariants before and after persistence. The service is
 * the only entry point the internal API uses.
 */
@Service
public class DocumentService {
    private final DocumentRepository repository;

    public DocumentService(DocumentRepository repository) {
        this.repository = repository;
    }

    public List<Document> list(UUID tenantId) {
        return repository.findAll(tenantId);
    }

    public Document get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    public Document create(CreateDocumentRequest request) {
        Instant now = Instant.now();
        DocumentRules.validateCreate(request, now);
        return repository.create(request.tenantId(), request, now);
    }

    public Document changeStatus(UUID tenantId, UUID id, UpdateDocumentStatusRequest request) {
        Document current = get(tenantId, id);
        DocumentRules.validateTransition(current, request.status(), request.expectedVersion());
        return repository.updateStatus(current, DocumentStatus.of(request.status()), Instant.now());
    }
}
