package com.eia.document.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import(DocumentService.class)
class DocumentControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentRepository repository;

    @MockitoBean
    private DocumentLinkRepository linkRepository;

    @MockitoBean
    private OutboxRepository outbox;

    @Test
    void createsDocumentAndReturnsGeneratedNumber() throws Exception {
        when(repository.create(eq(TENANT), any(CreateDocumentRequest.class), any(Instant.class)))
                .thenReturn(document("RECEIVED", RETENTION, 0));

        mockMvc.perform(post("/internal/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","title":"Policy schedule","documentType":"POLICY",
                                 "classification":"CONFIDENTIAL","retentionUntil":"2036-10-19"}
                                """.formatted(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentNumber").value("DOC-2026-0000001"))
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void rejectsUnknownDocumentType() throws Exception {
        mockMvc.perform(post("/internal/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","title":"Mystery","documentType":"NOT_A_TYPE",
                                 "classification":"INTERNAL"}
                                """.formatted(TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_TYPE"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnknownClassification() throws Exception {
        mockMvc.perform(post("/internal/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","title":"Policy","documentType":"POLICY",
                                 "classification":"TOP_SECRET"}
                                """.formatted(TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CLASSIFICATION"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsRetentionDateBeforeCreation() throws Exception {
        mockMvc.perform(post("/internal/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","title":"Old policy","documentType":"POLICY",
                                 "classification":"INTERNAL","retentionUntil":"2001-01-01"}
                                """.formatted(TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETENTION_BEFORE_CREATION"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingTitleWithFieldDetails() throws Exception {
        mockMvc.perform(post("/internal/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"%s","title":"","documentType":"POLICY",
                                 "classification":"INTERNAL"}
                                """.formatted(TENANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("title"));

        verifyNoInteractions(repository);
    }

    @Test
    void returnsNotFoundForUnknownDocument() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/v1/documents/{id}", DOCUMENT_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsIllegalLifecycleTransition() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("RECEIVED", RETENTION, 0)));

        mockMvc.perform(post("/internal/v1/documents/{id}/status", DOCUMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void requiresRetentionBeforeActivation() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("CLASSIFIED", null, 0)));

        mockMvc.perform(post("/internal/v1/documents/{id}/status", DOCUMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETENTION_REQUIRED_FOR_ACTIVE"));
    }

    @Test
    void rejectsStaleExpectedVersion() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("CLASSIFIED", RETENTION, 2)));

        mockMvc.perform(post("/internal/v1/documents/{id}/status", DOCUMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE","expectedVersion":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void advancesStatusAndIncrementsVersion() throws Exception {
        Document current = document("RECEIVED", RETENTION, 0);
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(current));
        when(repository.updateStatus(eq(current), eq(DocumentStatus.SCANNING), any(Instant.class)))
                .thenReturn(document("SCANNING", RETENTION, 1));

        mockMvc.perform(post("/internal/v1/documents/{id}/status", DOCUMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SCANNING","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNING"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/internal/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void exposesInternalSearchAlias() throws Exception {
        when(repository.findAll(TENANT, null, null, "DOC-2026"))
                .thenReturn(java.util.List.of(document("RECEIVED", RETENTION, 0)));

        mockMvc.perform(get("/internal/v1/documents/search")
                        .param("tenantId", TENANT.toString())
                        .param("q", "DOC-2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("DOC-2026"))
                .andExpect(jsonPath("$.items[0].label").value("DOC-2026-0000001"));
    }

    private static Document document(String status, LocalDate retentionUntil, int version) {
        Instant timestamp = Instant.parse("2026-10-19T12:00:00Z");
        return new Document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", status, retentionUntil, version, timestamp, timestamp);
    }
}
