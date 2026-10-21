package com.eia.document.expert;

import java.util.UUID;

/**
 * Resolves the tenant for the public read/link surface.
 *
 * <p>The public Document API uses {@code X-EIA-Tenant}; the expert's internal
 * surface uses a {@code tenantId} query parameter. Both are accepted here so
 * the same controller serves the gateway path and the internal alias. The
 * header wins when both are supplied.</p>
 */
final class TenantResolver {
    private TenantResolver() {
    }

    static UUID resolve(UUID headerTenant, UUID queryTenant) {
        if (headerTenant != null) {
            return headerTenant;
        }
        if (queryTenant != null) {
            return queryTenant;
        }
        throw new DocumentRuleException("TENANT_REQUIRED",
                "X-EIA-Tenant header or tenantId query parameter is required");
    }

    /**
     * Content routes treat an absent tenant as an invisible document: the
     * response is {@code 404 NOT_FOUND}, not a 400 that confirms the route was
     * reached. A supplied tenant still gates document lookup normally.
     */
    static UUID resolveForContent(UUID headerTenant, UUID queryTenant, UUID documentId) {
        if (headerTenant != null) {
            return headerTenant;
        }
        if (queryTenant != null) {
            return queryTenant;
        }
        throw new DocumentNotFoundException(documentId);
    }
}
