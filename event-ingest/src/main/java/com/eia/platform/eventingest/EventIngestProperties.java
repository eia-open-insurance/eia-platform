package com.eia.platform.eventingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the event inbox (prefix {@code eia.event-ingest}).
 */
@ConfigurationProperties(prefix = "eia.event-ingest")
public class EventIngestProperties {

    /**
     * Shared HMAC-SHA256 secret. When blank, signatures are not verified.
     * In deployments this is supplied through {@code EIA_EVENT_SIGNING_SECRET}.
     */
    private String signingSecret = "";

    /**
     * Upper bound accepted for {@code GET /api/v1/events/recent?limit=}.
     */
    private int maxRecentLimit = 200;

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
    }

    public int getMaxRecentLimit() {
        return maxRecentLimit;
    }

    public void setMaxRecentLimit(int maxRecentLimit) {
        this.maxRecentLimit = maxRecentLimit;
    }

    public int effectiveMaxRecentLimit() {
        return maxRecentLimit > 0 ? maxRecentLimit : 200;
    }
}
