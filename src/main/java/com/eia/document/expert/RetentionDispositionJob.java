package com.eia.document.expert;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quartz trigger for the daily retention disposition batch (SPEC 6.10).
 *
 * <p>The job is a thin adapter: it resolves {@link RetentionDispositionService}
 * through Spring's job factory, falling back to the scheduler context when the
 * job detail is built without autowiring, then delegates. Selection, per-document
 * transactions, failure isolation, and logging live in the service.</p>
 */
@DisallowConcurrentExecution
public class RetentionDispositionJob implements Job {
    public static final String SERVICE_CONTEXT_KEY = "eia.document.retention.service";

    private RetentionDispositionService service;

    public RetentionDispositionJob() {
    }

    @Autowired(required = false)
    public void setService(RetentionDispositionService service) {
        this.service = service;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        RetentionDispositionService target = this.service;
        if (target == null) {
            try {
                Object candidate = context.getScheduler().getContext().get(SERVICE_CONTEXT_KEY);
                if (candidate instanceof RetentionDispositionService contextual) {
                    target = contextual;
                }
            } catch (SchedulerException ex) {
                throw new JobExecutionException("Unable to read the retention scheduler context", ex);
            }
        }
        if (target == null) {
            throw new JobExecutionException(
                    "RetentionDispositionService is unavailable; check the retention configuration");
        }
        target.runBatch();
    }
}
