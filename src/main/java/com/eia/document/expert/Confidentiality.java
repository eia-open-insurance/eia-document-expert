package com.eia.document.expert;

import java.util.Arrays;
import java.util.Optional;

/**
 * Confidentiality classifications from SPEC section 11.6.5.
 */
public enum Confidentiality {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
    PHI,
    PRIVILEGED;

    public static Optional<Confidentiality> find(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.name().equals(raw)).findFirst();
    }

    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
