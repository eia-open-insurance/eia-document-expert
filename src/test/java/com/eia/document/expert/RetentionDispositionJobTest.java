package com.eia.document.expert;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RetentionDispositionJobTest {
    @Test
    void delegatesOneBatchToTheRetentionService() throws Exception {
        RetentionDispositionService service = mock(RetentionDispositionService.class);
        RetentionDispositionJob job = new RetentionDispositionJob();
        job.setService(service);

        job.execute(mock(JobExecutionContext.class));

        verify(service).runBatch();
    }
}
