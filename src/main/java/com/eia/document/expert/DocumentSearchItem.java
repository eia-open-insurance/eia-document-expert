package com.eia.document.expert;

import java.util.UUID;

/**
 * One row of the shared search response. A picker renders
 * {@code label}/{@code secondary} and navigates to {@code href}; the fields are
 * stable across all search-enabled services.
 */
public record DocumentSearchItem(
        UUID id,
        String type,
        String label,
        String secondary,
        String status,
        String href
) {}
