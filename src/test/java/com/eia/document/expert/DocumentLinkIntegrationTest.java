package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DocumentLinkIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID CLAIM_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID POLICY_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);

    @Autowired
    private DocumentService service;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM document_link");
        jdbc.update("DELETE FROM document");
    }

    @Test
    void addsListsAndRemovesLinkWithOutboxFacts() {
        Document document = service.create(request());
        DocumentLinkDto added = service.addLink(TENANT, document.id(),
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042"));

        assertThat(added.id()).isNotNull();
        assertThat(added.documentId()).isEqualTo(document.id());
        assertThat(added.linkedType()).isEqualTo("DIRECT_CLAIM");
        assertThat(added.linkedId()).isEqualTo(CLAIM_ID);
        assertThat(added.linkedReference()).isEqualTo("CLM-2026-000042");
        assertThat(added.linkedLabel()).isEqualTo("CLM-2026-000042");
        assertThat(added.createdAt()).isNotNull();

        assertThat(service.listLinks(TENANT, document.id()))
                .extracting(DocumentLinkDto::id)
                .containsExactly(added.id());

        List<Map<String, Object>> addedEvents = jdbc.queryForList("""
                SELECT id, event_type, aggregate_type, aggregate_version, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, added.id());
        assertThat(addedEvents).hasSize(1);
        assertThat(addedEvents.getFirst().get("event_type")).isEqualTo("document.link.added");
        assertThat(addedEvents.getFirst().get("aggregate_type")).isEqualTo("DocumentLink");
        assertThat(addedEvents.getFirst().get("aggregate_version")).isEqualTo(1L);
        String addedPayload = (String) addedEvents.getFirst().get("payload");
        assertThat(addedPayload)
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"documentNumber\":\"" + document.documentNumber() + "\"")
                .contains("\"linkId\":\"" + added.id() + "\"")
                .contains("\"linkedType\":\"DIRECT_CLAIM\"")
                .contains("\"linkedId\":\"" + CLAIM_ID + "\"")
                .contains("\"linkedReference\":\"CLM-2026-000042\"");
        assertThat((String) addedEvents.getFirst().get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"document\"");

        var storedAdded = outboxRepository.findById((UUID) addedEvents.getFirst().get("id"))
                .orElseThrow();
        var addedEnvelope = storedAdded.toEnvelope();
        assertThat(addedEnvelope.eventType()).isEqualTo("document.link.added");
        assertThat(addedEnvelope.data())
                .containsEntry("documentId", document.id().toString())
                .containsEntry("documentNumber", document.documentNumber())
                .containsEntry("linkId", added.id().toString())
                .containsEntry("linkedType", "DIRECT_CLAIM")
                .containsEntry("linkedId", CLAIM_ID.toString())
                .containsEntry("linkedReference", "CLM-2026-000042");

        service.removeLink(TENANT, document.id(), added.id());
        assertThat(service.listLinks(TENANT, document.id())).isEmpty();

        Map<String, Object> removedEvent = jdbc.queryForMap("""
                SELECT id, event_type, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'document.link.removed'
                """, added.id());
        assertThat(removedEvent.get("event_type")).isEqualTo("document.link.removed");
        assertThat((String) removedEvent.get("payload"))
                .contains("\"documentId\":\"" + document.id() + "\"")
                .contains("\"linkId\":\"" + added.id() + "\"")
                .contains("\"linkedType\":\"DIRECT_CLAIM\"")
                .contains("\"linkedId\":\"" + CLAIM_ID + "\"");
        var removedEnvelope = outboxRepository.findById((UUID) removedEvent.get("id"))
                .orElseThrow()
                .toEnvelope();
        assertThat(removedEnvelope.eventType()).isEqualTo("document.link.removed");
        assertThat(removedEnvelope.data())
                .containsEntry("documentId", document.id().toString())
                .containsEntry("linkId", added.id().toString());
    }

    @Test
    void duplicateLinkIsRejectedAndLeavesOneRowAndOneEvent() {
        Document document = service.create(request());
        CreateDocumentLinkRequest request =
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042");
        DocumentLinkDto link = service.addLink(TENANT, document.id(), request);

        assertThatThrownBy(() -> service.addLink(TENANT, document.id(), request))
                .isInstanceOf(DuplicateLinkException.class)
                .hasMessageContaining("already linked");

        assertThat(count("document_link")).isEqualTo(1);
        assertThat(countLinkEvents(link.id(), "document.link.added")).isEqualTo(1);
        assertThat(countLinkEvents(link.id(), "document.link.removed")).isZero();
    }

    @Test
    void linkWithNoReferenceFallsBackToShortIdLabel() {
        Document document = service.create(request());

        DocumentLinkDto link = service.addLink(TENANT, document.id(),
                new CreateDocumentLinkRequest("OTHER", POLICY_ID, "   "));

        assertThat(link.linkedReference()).isNull();
        assertThat(link.linkedLabel()).isEqualTo(POLICY_ID.toString().substring(0, 8));
    }

    @Test
    void documentOutsideTenantIsHiddenFromEveryLinkOperation() {
        Document document = service.create(request());
        DocumentLinkDto link = service.addLink(TENANT, document.id(),
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042"));

        assertThatThrownBy(() -> service.listLinks(OTHER_TENANT, document.id()))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.addLink(OTHER_TENANT, document.id(),
                new CreateDocumentLinkRequest("POLICY", POLICY_ID, null)))
                .isInstanceOf(DocumentNotFoundException.class);
        assertThatThrownBy(() -> service.removeLink(OTHER_TENANT, document.id(), link.id()))
                .isInstanceOf(DocumentNotFoundException.class);

        assertThat(count("document_link")).isEqualTo(1);
        assertThat(countLinkEvents(link.id(), "document.link.removed")).isZero();
    }

    @Test
    void linkOutsideTheDocumentIsNotFound() {
        Document first = service.create(request());
        Document second = service.create(request());
        DocumentLinkDto link = service.addLink(TENANT, first.id(),
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, null));

        assertThatThrownBy(() -> service.removeLink(TENANT, second.id(), link.id()))
                .isInstanceOf(DocumentLinkNotFoundException.class);
        assertThat(service.listLinks(TENANT, second.id())).isEmpty();
        assertThat(service.listLinks(TENANT, first.id())).hasSize(1);
    }

    @Test
    void filtersDocumentsByLinkedTypeAndId() {
        Document linked = service.create(request());
        Document unlinked = service.create(request());
        service.addLink(TENANT, linked.id(),
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042"));

        assertThat(service.list(TENANT, "DIRECT_CLAIM", CLAIM_ID, null))
                .extracting(Document::id)
                .containsExactly(linked.id());
        assertThat(service.list(TENANT, "POLICY", POLICY_ID, null)).isEmpty();
        assertThat(service.list(TENANT, "DIRECT_CLAIM", POLICY_ID, null)).isEmpty();
        assertThat(service.list(TENANT, null, null, null))
                .extracting(Document::id)
                .containsExactlyInAnyOrder(linked.id(), unlinked.id());

        assertThatThrownBy(() -> service.list(TENANT, "DIRECT_CLAIM", null, null))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo("INVALID_LINK_FILTER");
        assertThatThrownBy(() -> service.list(TENANT, "NOT_A_TYPE", CLAIM_ID, null))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo("INVALID_LINKED_TYPE");
    }

    @Test
    void searchMatchesLinkedReferenceAndDocumentMetadata() {
        Document linked = service.create(request());
        Document other = service.create(request());
        service.addLink(TENANT, linked.id(),
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042"));

        assertThat(service.search(TENANT, "000042", 10).items())
                .extracting(DocumentSearchItem::id)
                .containsExactly(linked.id());
        assertThat(service.search(TENANT, "clm-2026", 10).items())
                .extracting(DocumentSearchItem::id)
                .containsExactly(linked.id());
        assertThat(service.list(TENANT, null, null, "000042"))
                .extracting(Document::id)
                .containsExactly(linked.id());
        assertThat(service.search(TENANT, "Policy schedule", 10).items())
                .extracting(DocumentSearchItem::id)
                .containsExactlyInAnyOrder(linked.id(), other.id());
        assertThat(service.search(TENANT, "no-such-term", 10).items()).isEmpty();
    }

    @Test
    void searchLimitIsClampedAndEchoed() {
        service.create(request());

        DocumentSearchResponse response = service.search(TENANT, "  policy  ", 99);

        assertThat(response.query()).isEqualTo("policy");
        assertThat(response.limit()).isEqualTo(25);
        assertThat(service.search(TENANT, null, 0).limit()).isEqualTo(1);
    }

    @Test
    void addRollbackLeavesNoLinkAndNoEvent() {
        Document document = service.create(request());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.addLink(TENANT, document.id(),
                    new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("document_link")).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents("document.link.added")).isZero();
    }

    @Test
    void removeRollbackKeepsTheLinkAndWritesNoRemovalEvent() {
        Document document = service.create(request());
        DocumentLinkDto link = service.addLink(TENANT, document.id(),
                new CreateDocumentLinkRequest("DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042"));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.removeLink(TENANT, document.id(), link.id());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.listLinks(TENANT, document.id())).hasSize(1);
        assertThat(count("document_link")).isEqualTo(1);
        assertThat(countLinkEvents(link.id(), "document.link.removed")).isZero();
    }

    @Test
    void schemaDdlIsIdempotent() {
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(dataSource);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'document_link'",
                Integer.class)).isEqualTo(1);
    }

    private CreateDocumentRequest request() {
        return new CreateDocumentRequest(TENANT, "Policy schedule", "POLICY", "CONFIDENTIAL", RETENTION);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countLinkEvents(UUID linkId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, linkId, eventType);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?",
                Integer.class, eventType);
    }
}
