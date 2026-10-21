package com.eia.document.expert;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Comparator;
import java.util.List;

/**
 * Maps document failures onto structured HTTP responses: 400 for invalid input
 * and rule violations, 404 for an unknown document, link, content, or content
 * version, 409 for a version conflict, a duplicate link, or a lifecycle state
 * conflict (including an already-held document), and 413 for over-limit
 * content.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DocumentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError notFound(DocumentNotFoundException ex) {
        return new ApiError("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ContentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError contentNotFound(ContentNotFoundException ex) {
        return new ApiError("CONTENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ContentVersionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError contentVersionNotFound(ContentVersionNotFoundException ex) {
        return new ApiError("CONTENT_VERSION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(FileTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ApiError fileTooLarge(FileTooLargeException ex) {
        return new ApiError(DocumentContentRules.FILE_TOO_LARGE, ex.getMessage());
    }

    @ExceptionHandler(DocumentRuleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError ruleViolation(DocumentRuleException ex) {
        return new ApiError(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(VersionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError versionConflict(VersionConflictException ex) {
        return new ApiError("VERSION_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(DocumentConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError documentConflict(DocumentConflictException ex) {
        return new ApiError(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(DuplicateLinkException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError duplicateLink(DuplicateLinkException ex) {
        return new ApiError("DUPLICATE_LINK", ex.getMessage());
    }

    @ExceptionHandler(DocumentLinkNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError linkNotFound(DocumentLinkNotFoundException ex) {
        return new ApiError("LINK_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError validation(MethodArgumentNotValidException ex) {
        List<FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return new ApiError("VALIDATION_ERROR", "Request validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError malformed(HttpMessageNotReadableException ex) {
        return new ApiError("MALFORMED_REQUEST", "Request body is missing or is not valid JSON");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidParameter(Exception ex) {
        return new ApiError("INVALID_PARAMETER", ex.getMessage());
    }
}
