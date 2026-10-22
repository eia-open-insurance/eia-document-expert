package com.eia.document.expert;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automatic disposition is opt-in: the default configuration starts the
 * application without registering the Quartz job, while the job defaults stay
 * available for a later enablement.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetentionDispositionDisabledTest {
    @Autowired
    private RetentionDispositionProperties properties;

    @Autowired
    private Scheduler scheduler;

    @Test
    void disabledRetentionCreatesNoJobOrTrigger() throws Exception {
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.effectiveCron()).isEqualTo("0 0 2 * * ?");
        assertThat(properties.effectiveBatchSize()).isEqualTo(100);
        assertThat(properties.effectiveActorId()).isEqualTo(new UUID(0L, 0L));

        assertThat(scheduler.getJobDetail(new JobKey(
                DocumentRetentionSchedulingConfiguration.JOB_NAME,
                DocumentRetentionSchedulingConfiguration.JOB_GROUP))).isNull();
        assertThat(scheduler.getTrigger(new TriggerKey(
                DocumentRetentionSchedulingConfiguration.TRIGGER_NAME,
                DocumentRetentionSchedulingConfiguration.JOB_GROUP))).isNull();
        assertThat(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(
                DocumentRetentionSchedulingConfiguration.JOB_GROUP))).isEmpty();
    }
}
