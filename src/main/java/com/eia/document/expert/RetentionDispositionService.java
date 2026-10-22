package com.eia.document.expert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Runs one automatic retention disposition batch.
 *
 * <p>The batch is deliberately not transactional: each document is disposed by
 * {@link DocumentService#dispose(UUID, UUID, String, UUID)} in its own
 * transaction, so one failure neither prevents later documents from being
 * disposed nor rolls back work that already committed. A candidate that became
 * held or disposed between selection and disposition fails the service's own
 * invariant check and is counted as a failure while the batch continues.</p>
 *
 * <p>Selection is tenant-scoped: the job discovers the tenants with due
 * documents, then reads and disposes each tenant's rows under that tenant. The
 * configured batch size is a total across the run, not a per-tenant quota.</p>
 */
@Service
public class RetentionDispositionService {
    static final String DISPOSITION_REASON = "RETENTION_EXPIRED";

    private static final Logger log = LoggerFactory.getLogger(RetentionDispositionService.class);

    private final DocumentRepository repository;
    private final DocumentService documentService;
    private final RetentionDispositionProperties properties;

    public RetentionDispositionService(DocumentRepository repository,
                                       DocumentService documentService,
                                       RetentionDispositionProperties properties) {
        this.repository = repository;
        this.documentService = documentService;
        this.properties = properties;
    }

    /**
     * Disposes up to the configured batch size of due documents. Returns the
     * counts for the caller and writes one structured summary log line.
     */
    public RetentionDispositionSummary runBatch() {
        return runBatch(LocalDate.now(ZoneOffset.UTC));
    }

    RetentionDispositionSummary runBatch(LocalDate today) {
        int batchSize = properties.effectiveBatchSize();
        UUID actorId = properties.effectiveActorId();
        List<UUID> tenants = repository.findTenantsWithDueDocuments(today);

        int selected = 0;
        int disposed = 0;
        int failed = 0;

        for (UUID tenantId : tenants) {
            int remaining = batchSize - selected;
            if (remaining <= 0) {
                break;
            }
            for (DocumentRepository.DueDocument candidate
                    : repository.findDueForDisposition(tenantId, today, remaining)) {
                selected++;
                try {
                    documentService.dispose(candidate.tenantId(), candidate.documentId(),
                            DISPOSITION_REASON, actorId);
                    disposed++;
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("Retention disposition failed for document {} in tenant {}: {}",
                            candidate.documentId(), candidate.tenantId(), ex.getMessage());
                }
            }
        }

        log.info("Retention disposition batch finished: selected={}, disposed={}, failed={}, batchSize={}",
                selected, disposed, failed, batchSize);
        return new RetentionDispositionSummary(selected, disposed, failed);
    }

    /**
     * Counts from one batch run. {@code selected} is the number of candidates
     * read, {@code disposed} the number that reached DISPOSED, and
     * {@code failed} the number the service refused or could not dispose.
     */
    public record RetentionDispositionSummary(int selected, int disposed, int failed) {
    }
}
