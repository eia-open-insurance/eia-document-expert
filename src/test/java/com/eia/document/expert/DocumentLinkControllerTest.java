package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DocumentLinkController.class, DocumentQueryController.class})
@Import(DocumentService.class)
class DocumentLinkControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID LINK_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID CLAIM_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentRepository repository;

    @MockitoBean
    private DocumentLinkRepository linkRepository;

    @MockitoBean
    private OutboxRepository outbox;

    @Test
    void addsLinkAndReturnsCreatedDto() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"DIRECT_CLAIM","linkedId":"%s",
                                 "linkedReference":"CLM-2026-000042"}
                                """.formatted(CLAIM_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.documentId").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.linkedType").value("DIRECT_CLAIM"))
                .andExpect(jsonPath("$.linkedId").value(CLAIM_ID.toString()))
                .andExpect(jsonPath("$.linkedReference").value("CLM-2026-000042"))
                .andExpect(jsonPath("$.linkedLabel").value("CLM-2026-000042"))
                .andExpect(jsonPath("$.createdAt").exists());

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("document.link.added");
        assertThat(event.getValue().payload())
                .contains("\"documentNumber\":\"DOC-2026-0000001\"")
                .contains("\"linkedType\":\"DIRECT_CLAIM\"")
                .contains("\"linkedId\":\"" + CLAIM_ID + "\"")
                .contains("\"linkedReference\":\"CLM-2026-000042\"");
    }

    @Test
    void derivesShortLinkedLabelWhenReferenceIsMissing() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));
        when(linkRepository.findAll(TENANT, DOCUMENT_ID))
                .thenReturn(List.of(new DocumentLink(LINK_ID, DOCUMENT_ID, "PARTY", CLAIM_ID, null, NOW)));

        mockMvc.perform(get("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].linkedLabel").value(CLAIM_ID.toString().substring(0, 8)))
                .andExpect(jsonPath("$[0].linkedReference").doesNotExist());
    }

    @Test
    void rejectsDuplicateLinkWithConflict() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));
        when(linkRepository.exists(TENANT, DOCUMENT_ID, "DIRECT_CLAIM", CLAIM_ID)).thenReturn(true);

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"DIRECT_CLAIM","linkedId":"%s"}
                                """.formatted(CLAIM_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LINK"));

        verify(linkRepository, never()).insert(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsUnknownLinkedType() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"NOT_A_TYPE","linkedId":"%s"}
                                """.formatted(CLAIM_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINKED_TYPE"));

        verifyNoInteractions(linkRepository, outbox);
    }

    @Test
    void rejectsMissingLinkedId() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"DIRECT_CLAIM"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("linkedId"));

        verifyNoInteractions(repository, linkRepository, outbox);
    }

    @Test
    void hidesDocumentOutsideTenant() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"DIRECT_CLAIM","linkedId":"%s"}
                                """.formatted(CLAIM_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deletesLinkAndWritesRemovalEvent() throws Exception {
        DocumentLink link = new DocumentLink(
                LINK_ID, DOCUMENT_ID, "DIRECT_CLAIM", CLAIM_ID, "CLM-2026-000042", NOW);
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));
        when(linkRepository.findById(TENANT, DOCUMENT_ID, LINK_ID)).thenReturn(Optional.of(link));
        when(linkRepository.delete(TENANT, DOCUMENT_ID, LINK_ID)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/documents/{documentId}/links/{linkId}", DOCUMENT_ID, LINK_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("document.link.removed");
        assertThat(event.getValue().payload())
                .contains("\"linkId\":\"" + LINK_ID + "\"")
                .contains("\"linkedReference\":\"CLM-2026-000042\"");
    }

    @Test
    void returnsNotFoundForUnknownLink() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));
        when(linkRepository.findById(TENANT, DOCUMENT_ID, LINK_ID)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/documents/{documentId}/links/{linkId}", DOCUMENT_ID, LINK_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));

        verifyNoInteractions(outbox);
    }

    @Test
    void requiresTenant() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{documentId}/links", DOCUMENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void filtersDocumentListByLinkedSubject() throws Exception {
        when(repository.findAll(TENANT, "DIRECT_CLAIM", CLAIM_ID, null))
                .thenReturn(List.of(document()));

        mockMvc.perform(get("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("linkedType", "DIRECT_CLAIM")
                        .param("linkedId", CLAIM_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$[0].documentNumber").value("DOC-2026-0000001"));
    }

    @Test
    void rejectsPartialLinkFilter() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("linkedType", "DIRECT_CLAIM"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINK_FILTER"));

        verifyNoInteractions(repository);
    }

    @Test
    void searchesInEntityPickerShape() throws Exception {
        when(repository.findAll(TENANT, null, null, "CLM-2026-000042"))
                .thenReturn(List.of(document()));

        mockMvc.perform(get("/api/v1/documents/search")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("q", "CLM-2026-000042")
                        .param("limit", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("CLM-2026-000042"))
                .andExpect(jsonPath("$.limit").value(8))
                .andExpect(jsonPath("$.items[0].id").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].type").value("DOCUMENT"))
                .andExpect(jsonPath("$.items[0].label").value("DOC-2026-0000001"))
                .andExpect(jsonPath("$.items[0].secondary").value("Policy schedule · RECEIVED"))
                .andExpect(jsonPath("$.items[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].href").value("/document?record=" + DOCUMENT_ID));
    }

    @Test
    void listsLinkedDocumentsByFreeText() throws Exception {
        when(repository.findAll(TENANT, null, null, "000042"))
                .thenReturn(List.of(document()));

        mockMvc.perform(get("/api/v1/documents")
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("q", "000042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(DOCUMENT_ID.toString()));
    }

    @Test
    void acceptsInternalTenantQueryFallback() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(document()));
        when(linkRepository.findAll(TENANT, DOCUMENT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/internal/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void rejectsReferenceLongerThan64Characters() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/links", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"linkedType":"DIRECT_CLAIM","linkedId":"%s",
                                 "linkedReference":"%s"}
                                """.formatted(CLAIM_ID, "x".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("linkedReference"));

        verifyNoInteractions(repository, linkRepository, outbox);
    }

    private static Document document() {
        return new Document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", "RECEIVED", RETENTION, null, null, null, null,
                0, NOW, NOW);
    }
}
