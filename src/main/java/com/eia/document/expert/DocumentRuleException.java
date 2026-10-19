package com.eia.document.expert;

/**
 * A business-rule violation that must surface as a structured 400 response.
 */
public class DocumentRuleException extends RuntimeException {
    private final String code;

    public DocumentRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
