package com.eia.document.expert;

import java.util.Arrays;
import java.util.Optional;

/**
 * Subject kinds a document can be associated with through {@code document_link}.
 *
 * <p>The values are the closed vocabulary for the link API. They are stored as
 * {@code varchar} so the wire value is the enum name, matching the other
 * enumerations in this vertical.</p>
 */
public enum LinkedType {
    DIRECT_CLAIM,
    ASSUMED_CLAIM,
    POLICY,
    CONTRACT,
    PARTY,
    PAYMENT,
    OTHER;

    public static Optional<LinkedType> find(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.name().equals(raw)).findFirst();
    }

    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
