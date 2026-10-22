package com.eia.document.expert;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionDispositionServiceTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final RetentionDispositionProperties properties = new RetentionDispositionProperties();
    private final RetentionDispositionService service =
            new RetentionDispositionService(repository, documentService, properties);

    @Test
    void aFailedDocumentDoesNotStopLaterCandidates() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.findTenantsWithDueDocuments(TODAY)).thenReturn(List.of(TENANT));
        when(repository.findDueForDisposition(TENANT, TODAY, 100)).thenReturn(List.of(
                new DocumentRepository.DueDocument(TENANT, first, TODAY.minusDays(1)),
                new DocumentRepository.DueDocument(TENANT, second, TODAY)));
        when(documentService.dispose(TENANT, first, "RETENTION_EXPIRED", SYSTEM_ACTOR))
                .thenThrow(new DocumentConflictException(
                        DocumentRules.DOCUMENT_ON_LEGAL_HOLD, "document is under legal hold"));

        RetentionDispositionService.RetentionDispositionSummary summary =
                service.runBatch(TODAY);

        assertThat(summary).isEqualTo(
                new RetentionDispositionService.RetentionDispositionSummary(2, 1, 1));
        verify(documentService).dispose(TENANT, second, "RETENTION_EXPIRED", SYSTEM_ACTOR);
    }
}
