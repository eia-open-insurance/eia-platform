package com.eia.platform.libs.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the transactional outbox (prefix {@code eia.outbox}).
 *
 * <p>The three required controls are {@code enabled}, {@code endpoint}, and
 * {@code batchSize}. The remaining values tune retry and dispatch behaviour.</p>
 */
@ConfigurationProperties(prefix = "eia.outbox")
public class OutboxProperties {
    /**
     * Enables outbox beans and, in consuming services, the Quartz dispatch job.
     */
    private boolean enabled;

    /**
     * Subscriber endpoint. One endpoint is configured for the foundation;
     * subscriber routing by event type is a later subscription-registry step.
     */
    private String endpoint = "";

    /**
     * Maximum events claimed and dispatched per Quartz run.
     */
    private int batchSize = 100;

    /**
     * Failed attempts allowed before an event becomes DEAD (SPEC 7.5.1: 12).
     */
    private int maxAttempts = 12;

    /**
     * Visibility lease for a claimed row. A crashed dispatcher leaves the row
     * IN_FLIGHT only until this lease expires; the next claim reclaims it.
     */
    private Duration claimLease = Duration.ofMinutes(2);

    /**
     * Quartz repeat interval. SPEC 6.10 names a 2 second outbox dispatch cadence.
     */
    private Duration interval = Duration.ofSeconds(2);

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * Optional HMAC-SHA256 secret for X-EIA-Signature. When blank, the header
     * is omitted; production deployments should configure it.
     */
    private String signingSecret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = claimLease;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
    }

    public int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : 100;
    }

    public int effectiveMaxAttempts() {
        return maxAttempts > 0 ? maxAttempts : 12;
    }

    public Duration effectiveClaimLease() {
        return claimLease != null && !claimLease.isZero() && !claimLease.isNegative()
                ? claimLease
                : Duration.ofMinutes(2);
    }

    public Duration effectiveInterval() {
        return interval != null && !interval.isZero() && !interval.isNegative()
                ? interval
                : Duration.ofSeconds(2);
    }

    public Duration effectiveConnectTimeout() {
        return connectTimeout != null && !connectTimeout.isNegative()
                ? connectTimeout
                : Duration.ofSeconds(5);
    }

    public Duration effectiveReadTimeout() {
        return readTimeout != null && !readTimeout.isNegative()
                ? readTimeout
                : Duration.ofSeconds(10);
    }
}
