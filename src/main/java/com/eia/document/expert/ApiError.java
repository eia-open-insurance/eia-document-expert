package com.eia.document.expert;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Structured error envelope. Mirrors the domain service shape so callers can
 * reason about failures without parsing prose.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldViolation> details) {
    public ApiError(String code, String message) {
        this(code, message, List.of());
    }
}
