package com.eia.document.expert;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Public document list and search routes used by the shell and pickers.
 *
 * <p>{@code GET /api/v1/documents} accepts the optional
 * {@code linkedType}/{@code linkedId} pair and an optional free-text
 * {@code q}. {@code GET /api/v1/documents/search} returns the shared
 * {@code {query, limit, items}} envelope that EntityPicker consumes. Both
 * resolve the tenant from {@code X-EIA-Tenant} with the internal
 * {@code tenantId} query parameter as a fallback.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentQueryController {
    private static final String TENANT_HEADER = "X-EIA-Tenant";

    private final DocumentService service;

    public DocumentQueryController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Document> list(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @RequestParam(name = "linkedType", required = false) String linkedType,
            @RequestParam(name = "linkedId", required = false) UUID linkedId,
            @RequestParam(name = "q", required = false) String q) {
        return service.list(TenantResolver.resolve(headerTenant, queryTenant),
                linkedType, linkedId, q);
    }

    @GetMapping("/search")
    public DocumentSearchResponse search(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return service.search(TenantResolver.resolve(headerTenant, queryTenant), q, limit);
    }
}
