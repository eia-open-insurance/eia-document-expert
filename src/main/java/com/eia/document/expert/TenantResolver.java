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
}
