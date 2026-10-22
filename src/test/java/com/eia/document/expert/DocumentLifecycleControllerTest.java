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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentLifecycleController.class)
@Import(DocumentService.class)
class DocumentLifecycleControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID DOCUMENT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate RETENTION = LocalDate.of(2036, 10, 19);
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentRepository repository;

    @MockitoBean
    private DocumentLinkRepository linkRepository;

    @MockitoBean
    private DocumentContentRepository contentRepository;

    @MockitoBean
    private OutboxRepository outbox;

    @Test
    void placesHoldAndReturnsTheSnapshotFields() throws Exception {
        Document current = document("ACTIVE", RETENTION, null, null, null, null, 2);
        Document held = document("ON_HOLD", RETENTION, "Litigation hold", NOW, "ACTIVE", null, 3);
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(current));
        when(repository.placeHold(eq(current), eq("Litigation hold"), any(Instant.class)))
                .thenReturn(held);

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.holdReason").value("Litigation hold"))
                .andExpect(jsonPath("$.statusBeforeHold").value("ACTIVE"))
                .andExpect(jsonPath("$.heldAt").exists())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void releasesHoldAndRestoresThePreviousStatus() throws Exception {
        Document current = document("ON_HOLD", RETENTION, "Litigation hold", NOW, "CLASSIFIED", null, 3);
        Document released = document("CLASSIFIED", RETENTION, null, null, null, null, 4);
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(current));
        when(repository.releaseHold(eq(current), eq(DocumentStatus.CLASSIFIED), any(Instant.class)))
                .thenReturn(released);

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold/release", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLASSIFIED"))
                .andExpect(jsonPath("$.holdReason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.statusBeforeHold").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void rejectsASecondHoldWithConflict() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("ON_HOLD", RETENTION,
                        "Litigation hold", NOW, "ACTIVE", null, 3)));

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Another hold"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DocumentRules.DOCUMENT_ALREADY_ON_HOLD));
    }

    @Test
    void rejectsReleaseWhenNotHeld() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("ACTIVE", RETENTION, null, null, null, null, 2)));

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold/release", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DocumentRules.DOCUMENT_NOT_ON_HOLD));
    }

    @Test
    void rejectsDispositionBeforeRetentionExpires() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("ACTIVE", RETENTION, null, null, null, null, 2)));

        mockMvc.perform(post("/api/v1/documents/{documentId}/dispose", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DocumentRules.RETENTION_NOT_EXPIRED));
    }

    @Test
    void rejectsDispositionWhileOnHold() throws Exception {
        when(repository.findById(TENANT, DOCUMENT_ID))
                .thenReturn(Optional.of(document("ON_HOLD", LocalDate.now(ZoneOffset.UTC),
                        "Litigation hold", NOW, "ACTIVE", null, 3)));

        mockMvc.perform(post("/api/v1/documents/{documentId}/dispose", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(DocumentRules.DOCUMENT_ON_LEGAL_HOLD));
    }

    @Test
    void disposesADocumentWhoseRetentionExpired() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Document current = document("ACTIVE", today, null, null, null, null, 2);
        Document disposed = document("DISPOSED", today, null, null, null, NOW, 3);
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(current));
        when(repository.dispose(eq(current), any(Instant.class))).thenReturn(disposed);

        mockMvc.perform(post("/api/v1/documents/{documentId}/dispose", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPOSED"))
                .andExpect(jsonPath("$.disposedAt").exists());
    }

    @Test
    void scopesLifecycleWritesToTheHeaderTenant() throws Exception {
        when(repository.findById(OTHER_TENANT, DOCUMENT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void acceptsTheInternalTenantQueryFallback() throws Exception {
        Document current = document("ACTIVE", RETENTION, null, null, null, null, 2);
        Document held = document("ON_HOLD", RETENTION, "Litigation hold", NOW, "ACTIVE", null, 3);
        when(repository.findById(TENANT, DOCUMENT_ID)).thenReturn(Optional.of(current));
        when(repository.placeHold(eq(current), eq("Litigation hold"), any(Instant.class)))
                .thenReturn(held);

        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_HOLD"));
    }

    @Test
    void requiresATenant() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Litigation hold"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));

        verifyNoInteractions(repository, linkRepository, outbox);
    }

    @Test
    void rejectsHoldReasonsOverTheLimit() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"%s\"}".formatted("x".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("reason"));

        verifyNoInteractions(repository, linkRepository, outbox);
    }

    @Test
    void rejectsBlankHoldReasons() throws Exception {
        mockMvc.perform(post("/api/v1/documents/{documentId}/hold", DOCUMENT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("reason"));

        verifyNoInteractions(repository, linkRepository, outbox);
    }

    private static Document document(String status, LocalDate retentionUntil, String holdReason,
                                     Instant heldAt, String statusBeforeHold, Instant disposedAt,
                                     int version) {
        return new Document(DOCUMENT_ID, TENANT, "DOC-2026-0000001", "Policy schedule",
                "POLICY", "CONFIDENTIAL", status, retentionUntil, holdReason, heldAt,
                statusBeforeHold, disposedAt, version, NOW, NOW);
    }
}
