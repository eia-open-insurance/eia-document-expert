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
    public List<Document> list(@RequestParam UUID tenantId,
                               @RequestParam(name = "linkedType", required = false) String linkedType,
                               @RequestParam(name = "linkedId", required = false) UUID linkedId,
                               @RequestParam(name = "q", required = false) String q) {
        return service.list(tenantId, linkedType, linkedId, q);
    }

    /**
     * Internal search projection. The public surface lives on
     * {@link DocumentQueryController}; this alias keeps the expert's own API
     * usable by the domain's eventually-consistent read path.
     */
    @GetMapping("/search")
    public DocumentSearchResponse search(@RequestParam UUID tenantId,
                                         @RequestParam(name = "q", required = false) String q,
                                         @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.search(tenantId, q, limit);
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
