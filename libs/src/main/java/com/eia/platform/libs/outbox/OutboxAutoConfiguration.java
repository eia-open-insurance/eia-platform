package com.eia.platform.libs.outbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Wires outbox persistence whenever JDBC is present. HTTP dispatch beans are
 * created only when {@code eia.outbox.enabled=true}; the consuming service
 * always writes the outbox row in its business transaction, so disabling
 * dispatch never loses an event. Scheduling stays with the consuming service
 * so it can choose its Quartz topology and cadence.
 */
@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, RestClient.class})
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) {
        return new OutboxRepository(jdbcTemplate);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "eia.outbox", name = "enabled", havingValue = "true")
    static class DispatchConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public OutboxDispatcher outboxDispatcher(OutboxProperties properties) {
            return new RestClientOutboxDispatcher(
                    RestClientOutboxDispatcher.defaultRestClient(properties), properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public OutboxDispatchService outboxDispatchService(
                OutboxRepository repository,
                OutboxDispatcher dispatcher,
                OutboxProperties properties) {
            return new OutboxDispatchService(repository, dispatcher, properties, Clock.systemUTC());
        }
    }
}
