package com.eia.document.expert;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Document lifecycle states from SPEC section 11.6.5 and the permitted
 * transitions from the state diagram in SPEC section 11.6.6.
 */
public enum DocumentStatus {
    PENDING_UPLOAD,
    RECEIVED,
    SCANNING,
    PROCESSING,
    CLASSIFIED,
    ACTIVE,
    SUPERSEDED,
    ON_HOLD,
    ARCHIVED,
    DISPOSED,
    REJECTED,
    FAILED;

    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = new EnumMap<>(DocumentStatus.class);

    static {
        TRANSITIONS.put(PENDING_UPLOAD, EnumSet.of(RECEIVED));
        TRANSITIONS.put(RECEIVED, EnumSet.of(SCANNING));
        TRANSITIONS.put(SCANNING, EnumSet.of(PROCESSING, REJECTED));
        TRANSITIONS.put(PROCESSING, EnumSet.of(CLASSIFIED, FAILED));
        TRANSITIONS.put(CLASSIFIED, EnumSet.of(ACTIVE));
        TRANSITIONS.put(ACTIVE, EnumSet.of(SUPERSEDED, ON_HOLD, ARCHIVED, DISPOSED));
        TRANSITIONS.put(ON_HOLD, EnumSet.of(ACTIVE));
        TRANSITIONS.put(ARCHIVED, EnumSet.of(DISPOSED, ON_HOLD));
        TRANSITIONS.put(FAILED, EnumSet.of(PROCESSING));
        TRANSITIONS.put(SUPERSEDED, EnumSet.noneOf(DocumentStatus.class));
        TRANSITIONS.put(DISPOSED, EnumSet.noneOf(DocumentStatus.class));
        TRANSITIONS.put(REJECTED, EnumSet.noneOf(DocumentStatus.class));
    }

    public Set<DocumentStatus> allowedNext() {
        return Set.copyOf(TRANSITIONS.get(this));
    }

    public boolean canTransitionTo(DocumentStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    public static Optional<DocumentStatus> find(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.name().equals(raw)).findFirst();
    }

    public static DocumentStatus of(String raw) {
        return find(raw).orElseThrow(() -> new IllegalArgumentException("Unknown document status: " + raw));
    }

    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
