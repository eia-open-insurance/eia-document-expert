package com.eia.document.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Association-link API for a document.
 *
 * <p>The public routes are {@code /api/v1/documents}; the same operations are
 * also exposed on the expert's {@code /internal/v1/documents} base for the
 * Document domain service. The tenant comes from {@code X-EIA-Tenant}, with
 * the internal {@code tenantId} query parameter accepted as a fallback.</p>
 *
 * <p>Duplicate links are rejected with {@code 409 DUPLICATE_LINK}; a document
 * outside the tenant and a link outside the tenant/document pair both return
 * {@code 404}. See the README for the contract.</p>
 */
@RestController
@RequestMapping({"/api/v1/documents", "/internal/v1/documents"})
public class DocumentLinkController {
    private static final String TENANT_HEADER = "X-EIA-Tenant";

    private final DocumentService service;

    public DocumentLinkController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}/links")
    public List<DocumentLinkDto> list(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId) {
        return service.listLinks(TenantResolver.resolve(headerTenant, queryTenant), documentId);
    }

    @PostMapping("/{documentId}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentLinkDto add(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId,
            @Valid @RequestBody CreateDocumentLinkRequest request) {
        return service.addLink(TenantResolver.resolve(headerTenant, queryTenant), documentId, request);
    }

    @DeleteMapping("/{documentId}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId,
            @PathVariable UUID linkId) {
        service.removeLink(TenantResolver.resolve(headerTenant, queryTenant), documentId, linkId);
    }
}
