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
 * Events for document association links.
 *
 * <p>The event types are canonical three-segment names
 * ({@code document.link.added} / {@code document.link.removed}) so the shared
 * {@link EventEnvelope} validation accepts them and the outbox dispatcher can
 * reconstruct and publish them. The aggregate is {@code DocumentLink} with the
 * link id and version 1, so two links on the same document cannot collide on
 * the outbox aggregate-version key.</p>
 */
final class DocumentLinkEvents {
    static final String LINK_ADDED = "document.link.added";
    static final String LINK_REMOVED = "document.link.removed";

    private static final String VERTICAL = "document";
    private static final String AGGREGATE_TYPE = "DocumentLink";
    private static final String SOURCE_BUILD = "eia-document-expert@0.1.0-SNAPSHOT";

    private DocumentLinkEvents() {
    }

    static EventEnvelope added(Document document, DocumentLink link, Instant now) {
        return envelope(document, link, LINK_ADDED, now);
    }

    static EventEnvelope removed(Document document, DocumentLink link, Instant now) {
        return envelope(document, link, LINK_REMOVED, now);
    }

    private static EventEnvelope envelope(
            Document document, DocumentLink link, String eventType, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", document.id());
        data.put("documentNumber", document.documentNumber());
        data.put("linkId", link.id());
        data.put("linkedType", link.linkedType());
        data.put("linkedId", link.linkedId());
        data.put("linkedReference", link.linkedReference());

        UUID eventId = EventIds.deterministic(
                document.tenantId(), AGGREGATE_TYPE, link.id(), 1L, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(document.tenantId())
                .book(Book.DIRECT)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(link.id())
                .aggregateVersion(1L)
                .sequence(0)
                .actor(EventActor.system("eia-document-expert"))
                .subject(EventSubject.of(VERTICAL, "document-link", link.id(), link.linkedLabel()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }
}
