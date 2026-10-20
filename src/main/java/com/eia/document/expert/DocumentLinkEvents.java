package com.eia.document.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxHeaders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox rows for document association links.
 *
 * <p>The link facts use the names requested by the link API:
 * {@code document.link-added} and {@code document.link-removed}. Those names
 * have two segments, whereas the shared canonical {@link
 * com.eia.platform.libs.events.EventEnvelope} requires three
 * ({@code vertical.aggregate.verb}); the row is therefore assembled directly
 * with the canonical headers and data instead of going through
 * {@code EventEnvelope}. The aggregate is {@code DocumentLink} so two links on
 * the same document cannot collide on the outbox aggregate-version key.</p>
 */
final class DocumentLinkEvents {
    static final String LINK_ADDED = "document.link-added";
    static final String LINK_REMOVED = "document.link-removed";

    private static final String VERTICAL = "document";
    private static final String AGGREGATE_TYPE = "DocumentLink";
    private static final String SOURCE_BUILD = "eia-document-expert@0.1.0-SNAPSHOT";

    private DocumentLinkEvents() {
    }

    static OutboxEvent added(Document document, DocumentLink link, Instant now) {
        return event(document, link, LINK_ADDED, now);
    }

    static OutboxEvent removed(Document document, DocumentLink link, Instant now) {
        return event(document, link, LINK_REMOVED, now);
    }

    private static OutboxEvent event(
            Document document, DocumentLink link, String eventType, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", document.id());
        data.put("documentNumber", document.documentNumber());
        data.put("linkId", link.id());
        data.put("linkedType", link.linkedType());
        data.put("linkedId", link.linkedId());
        data.put("linkedReference", link.linkedReference());

        EventSubject subject = EventSubject.of(
                VERTICAL, "document-link", link.id(), link.linkedLabel());
        OutboxHeaders headers = new OutboxHeaders(
                Book.DIRECT,
                VERTICAL,
                null,
                null,
                null,
                EventActor.system("eia-document-expert"),
                subject,
                List.of(),
                EventMetadata.v1(SOURCE_BUILD));

        UUID eventId = EventIds.deterministic(
                document.tenantId(), AGGREGATE_TYPE, link.id(), 1L, eventType);
        return new OutboxEvent(
                eventId,
                0L,
                document.tenantId(),
                AGGREGATE_TYPE,
                link.id(),
                1L,
                eventType,
                1,
                EventJson.toJson(data),
                EventJson.toJson(headers),
                link.id().toString(),
                now,
                now,
                now,
                OutboxEvent.Status.PENDING,
                0,
                null,
                now,
                null);
    }
}
