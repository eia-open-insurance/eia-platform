package com.eia.platform.eventingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Clock injection keeps ingest timestamps deterministic in tests.
 */
@Configuration(proxyBeanMethods = false)
public class EventIngestConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
