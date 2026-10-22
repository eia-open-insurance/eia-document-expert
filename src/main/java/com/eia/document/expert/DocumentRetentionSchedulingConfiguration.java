package com.eia.document.expert;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Registers the automatic retention disposition trigger only when
 * {@code eia.document.retention.enabled=true}. Quartz is the only scheduler
 * (SPEC 6.10, C-04), so the job runs on a cron trigger in UTC; the default
 * expression is daily at 02:00.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "eia.document.retention", name = "enabled", havingValue = "true")
public class DocumentRetentionSchedulingConfiguration {
    public static final String JOB_GROUP = "eia-document";
    public static final String JOB_NAME = "document.retention-disposition";
    public static final String TRIGGER_NAME = JOB_NAME + ".daily";

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Bean
    public ScheduledRetentionDispositionJob scheduledRetentionDispositionJob(
            Scheduler scheduler,
            RetentionDispositionService service,
            RetentionDispositionProperties properties) throws SchedulerException {
        scheduler.getContext().put(RetentionDispositionJob.SERVICE_CONTEXT_KEY, service);

        JobDetail job = JobBuilder.newJob(RetentionDispositionJob.class)
                .withIdentity(JOB_NAME, JOB_GROUP)
                .withDescription("Dispose documents whose retention date has expired")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(TRIGGER_NAME, JOB_GROUP)
                .forJob(job)
                .withSchedule(CronScheduleBuilder.cronSchedule(properties.effectiveCron())
                        .inTimeZone(UTC)
                        .withMisfireHandlingInstructionDoNothing())
                .build();

        if (scheduler.checkExists(job.getKey())) {
            scheduler.deleteJob(job.getKey());
        }
        scheduler.scheduleJob(job, trigger);

        return new ScheduledRetentionDispositionJob(job.getKey(), trigger.getKey());
    }

    public record ScheduledRetentionDispositionJob(JobKey jobKey, TriggerKey triggerKey) {
    }
}
