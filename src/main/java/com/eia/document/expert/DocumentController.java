package com.eia.document.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Internal API consumed by the Document domain service.
 */
@RestController
@RequestMapping("/internal/v1/documents")
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Document> list(@RequestParam UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{id}")
    public Document get(@RequestParam UUID tenantId, @PathVariable UUID id) {
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Document create(@Valid @RequestBody CreateDocumentRequest request) {
        return service.create(request);
    }

    @PostMapping("/{id}/status")
    public Document changeStatus(@RequestParam UUID tenantId,
                                 @PathVariable UUID id,
                                 @Valid @RequestBody UpdateDocumentStatusRequest request) {
        return service.changeStatus(tenantId, id, request);
    }
}
