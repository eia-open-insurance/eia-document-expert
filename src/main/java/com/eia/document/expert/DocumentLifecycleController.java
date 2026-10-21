package com.eia.document.expert;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Legal-hold and disposition routes for a single document.
 *
 * <ul>
 *   <li>{@code POST /api/v1/documents/{documentId}/hold} places a hold from
 *       ACTIVE, ARCHIVED, CLASSIFIED, or SUPERSEDED and snapshots the prior
 *       status. The body is {@code {reason}} (at most 500 characters). A second
 *       hold is {@code 409 DOCUMENT_ALREADY_ON_HOLD}; a disallowed status is
 *       {@code 409 HOLD_STATUS_NOT_ALLOWED}.</li>
 *   <li>{@code POST .../hold/release} restores the snapshotted status (ARCHIVED
 *       when the snapshot is absent) and clears the hold columns. A document
 *       that is not held is {@code 409 DOCUMENT_NOT_ON_HOLD}.</li>
 *   <li>{@code POST .../dispose} tombstones the document only when its
 *       retention date is set and has expired and the document is not under
 *       legal hold. The optional body is {@code {reason}}; the default event
 *       reason is {@code RETENTION_EXPIRED}. A premature or hold-blocked
 *       disposition is {@code 409}.</li>
 * </ul>
 *
 * <p>The tenant comes from {@code X-EIA-Tenant}; the internal {@code tenantId}
 * query parameter is accepted as a fallback, matching the rest of the public
 * Document API. A document outside that tenant is {@code 404}, so the tenant
 * wall never reveals another tenant's document.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentLifecycleController {
    private static final String TENANT_HEADER = "X-EIA-Tenant";

    private final DocumentService service;

    public DocumentLifecycleController(DocumentService service) {
        this.service = service;
    }

    @PostMapping("/{documentId}/hold")
    public Document hold(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId,
            @Valid @RequestBody HoldDocumentRequest request) {
        return service.placeHold(TenantResolver.resolve(headerTenant, queryTenant),
                documentId, request.reason());
    }

    @PostMapping("/{documentId}/hold/release")
    public Document release(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId) {
        return service.releaseHold(TenantResolver.resolve(headerTenant, queryTenant), documentId);
    }

    @PostMapping("/{documentId}/dispose")
    public Document dispose(
            @RequestHeader(name = TENANT_HEADER, required = false) UUID headerTenant,
            @RequestParam(name = "tenantId", required = false) UUID queryTenant,
            @PathVariable UUID documentId,
            @Valid @RequestBody(required = false) DisposeDocumentRequest request) {
        return service.dispose(TenantResolver.resolve(headerTenant, queryTenant), documentId,
                request == null ? null : request.reason());
    }
}
