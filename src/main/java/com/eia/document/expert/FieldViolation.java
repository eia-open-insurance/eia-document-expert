package com.eia.document.expert;

/**
 * One field-level validation failure inside an {@link ApiError}.
 */
public record FieldViolation(String field, String message) {}
