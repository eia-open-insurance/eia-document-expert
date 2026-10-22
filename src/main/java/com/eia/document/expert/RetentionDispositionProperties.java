package com.eia.document.expert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/**
 * Configuration for automatic retention disposition (prefix
 * {@code eia.document.retention}).
 *
 * <p>The job is opt-in: {@code enabled} defaults to false so a deployment that
 * has not approved automatic destruction never schedules it. When enabled, the
 * Quartz trigger runs on the configured cron expression (daily at 02:00 UTC by
 * default) and processes at most {@code batchSize} documents per run.</p>
 *
 * <p>Disposition writes the same {@code document.document.disposed} fact as a
 * manual dispose, but under a system actor. The default actor id is the
 * all-zeros UUID; deployments can point it at a dedicated job identity.</p>
 */
@ConfigurationProperties(prefix = "eia.document.retention")
public class RetentionDispositionProperties {
    /** Daily at 02:00 UTC, matching the retention scan slot. */
    public static final String DEFAULT_CRON = "0 0 2 * * ?";
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** The all-zeros UUID represents the unattended system actor. */
    public static final UUID DEFAULT_ACTOR_ID = new UUID(0L, 0L);

    private boolean enabled;

    private String cron = DEFAULT_CRON;

    private int batchSize = DEFAULT_BATCH_SIZE;

    private UUID actorId = DEFAULT_ACTOR_ID;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String effectiveCron() {
        return cron == null || cron.isBlank() ? DEFAULT_CRON : cron.trim();
    }

    public int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    public UUID effectiveActorId() {
        return actorId == null ? DEFAULT_ACTOR_ID : actorId;
    }
}
