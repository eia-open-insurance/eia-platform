package com.eia.platform.eventingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Event ingest / inbox service.
 *
 * <p>Receives the canonical EIA event envelope from expert-service outbox
 * dispatchers over authenticated HTTP (there is no broker, ADR-0004), stores
 * it idempotently by {@code eventId}, and exposes the recent stream and counts
 * used by the shell dashboard.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(EventIngestProperties.class)
public class EventIngestApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventIngestApplication.class, args);
    }
}
