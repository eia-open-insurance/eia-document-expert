package com.eia.document.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Document vertical. Event names are the Document slice
 * names; they differ from the planned catalog names (see the rollout report).
 *
 * <p>Document is shared metadata used from both books and the current Document
 * API has no book input; the canonical envelope requires a book, so these
 * events are stamped DIRECT until book scoping reaches the Document API.</p>
 *
 * <p>Rows start at version 0 for API compatibility, while the canonical envelope
 * requires {@code aggregateVersion >= 1}; the event version is therefore the row
 * version plus one (create -> 1, first update -> 2).</p>
 */
final class DocumentEvents {
    static final String CREATED = "document.document.created";
    static final String UPDATED = "document.document.updated";
    static final String STATUS_CHANGED = "document.document.status-changed";
    static final String LEGAL_HOLD_PLACED = "document.legal-hold.placed";
    static final String LEGAL_HOLD_RELEASED = "document.legal-hold.released";
    /**
     * Canonical catalog name. The platform envelope requires the
     * {@code {vertical}.{aggregate}.{verb}} shape
     * ({@code document.document.disposed}), so the shorter fact name
     * {@code document.disposed} is not a valid wire event type.
     */
    static final String DISPOSED = "document.document.disposed";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "document";
    private static final String AGGREGATE_TYPE = "Document";
    private static final String SOURCE_BUILD = "eia-document-expert@0.1.0-SNAPSHOT";

    private DocumentEvents() {
    }

    static EventEnvelope created(Document document, Instant now) {
        Map<String, Object> data = identity(document);
        data.put("title", document.title());
        data.put("documentType", document.documentType());
        data.put("classification", document.classification());
        data.put("status", document.status());
        if (document.retentionUntil() != null) {
            data.put("retentionUntil", document.retentionUntil().toString());
        }
        data.put("version", document.version());

        return envelope(document, CREATED, document.version() + 1L, now, data);
    }

    /**
     * Coarse aggregate-change fact for a lifecycle write. It is emitted together
     * with {@link #statusChanged} at the same aggregate version.
     */
    static EventEnvelope updated(Document before, Document after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("changedFields", List.of("status"));
        data.put("version", after.version());
        data.put("from", before.status());
        data.put("to", after.status());

        return envelope(after, UPDATED, after.version() + 1L, now, data);
    }

    static EventEnvelope statusChanged(Document before, Document after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("status", after.status());
        data.put("version", after.version());
        data.put("from", before.status());
        data.put("to", after.status());

        return envelope(after, STATUS_CHANGED, after.version() + 1L, now, data);
    }

    /**
     * Legal-hold fact for a document that just entered {@code ON_HOLD}. The
     * payload is the shared lifecycle shape: document identity, hold reason, and
     * the resulting status.
     */
    static EventEnvelope legalHoldPlaced(Document document, String reason, Instant now) {
        return lifecycleEvent(document, LEGAL_HOLD_PLACED, reason, now);
    }

    /**
     * Release fact. The reason is the hold reason being lifted; the status is
     * the restored pre-hold status.
     */
    static EventEnvelope legalHoldReleased(Document document, String reason, Instant now) {
        return lifecycleEvent(document, LEGAL_HOLD_RELEASED, reason, now);
    }

    /** Disposition tombstone fact for a document moving to {@code DISPOSED}. */
    static EventEnvelope disposed(Document document, String reason, Instant now) {
        return lifecycleEvent(document, DISPOSED, reason, now);
    }

    private static EventEnvelope lifecycleEvent(
            Document document, String eventType, String reason, Instant now) {
        Map<String, Object> data = identity(document);
        data.put("reason", reason);
        data.put("status", document.status());

        return envelope(document, eventType, document.version() + 1L, now, data);
    }

    private static Map<String, Object> identity(Document document) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", document.id());
        data.put("documentNumber", document.documentNumber());
        return data;
    }

    private static EventEnvelope envelope(
            Document document, String eventType, long aggregateVersion, Instant now,
            Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                document.tenantId(), AGGREGATE_TYPE, document.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(document.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(document.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-document-expert"))
                .subject(EventSubject.of(VERTICAL, "document", document.id(), document.documentNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
