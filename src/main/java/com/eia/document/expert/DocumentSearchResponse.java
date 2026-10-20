package com.eia.document.expert;

import java.util.List;

/**
 * Shared search envelope consumed by the shell and EntityPicker.
 * {@code query} echoes the trimmed term and {@code limit} is the effective
 * limit after the default/cap rules are applied.
 */
public record DocumentSearchResponse(
        String query,
        int limit,
        List<DocumentSearchItem> items
) {}
