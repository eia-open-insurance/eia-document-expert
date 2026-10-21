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
 * Event factory for stored document content.
 *
 * <p>The event is written to the outbox in the same transaction as the
 * revision row. {@code DocumentContent} is its own aggregate, keyed by the
 * document id; the revision number is the aggregate version, so every upload
 * produces a new event instead of colliding with the previous upload's outbox
 * unique key. The payload repeats the revision as {@code version}.</p>
 */
final class DocumentContentEvents {
    static final String UPLOADED = "document.content.uploaded";

    private static final String VERTICAL = "document";
    private static final String AGGREGATE_TYPE = "DocumentContent";
    private static final String SOURCE_BUILD = "eia-document-expert@0.1.0-SNAPSHOT";

    private DocumentContentEvents() {
    }

    static EventEnvelope uploaded(Document document, DocumentContentVersion content, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", document.id());
        data.put("documentNumber", document.documentNumber());
        data.put("version", content.version());
        data.put("fileName", content.fileName());
        data.put("contentType", content.contentType());
        data.put("sizeBytes", content.sizeBytes());
        data.put("sha256", content.sha256());

        UUID eventId = EventIds.deterministic(
                document.tenantId(), AGGREGATE_TYPE, document.id(),
                content.version(), UPLOADED);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(UPLOADED)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(document.tenantId())
                .book(Book.DIRECT)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(document.id())
                .aggregateVersion(content.version())
                .sequence(0)
                .actor(EventActor.system("eia-document-expert"))
                .subject(EventSubject.of(VERTICAL, "document-content", document.id(),
                        document.documentNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
