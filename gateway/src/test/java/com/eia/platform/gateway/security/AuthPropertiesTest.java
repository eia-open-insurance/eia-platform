package com.eia.platform.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the code-level defaults and relaxed binding of {@code eia.auth}
 * independently of the packaged {@code application.yml}.
 */
class AuthPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsToDisabledWithLocalIssuerAndJwks() {
        runner.run(context -> {
            AuthProperties properties = context.getBean(AuthProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.jwksUri()).isEqualTo("http://localhost:9000/oauth2/jwks");
            assertThat(properties.issuer()).isEqualTo("http://localhost:9000");
        });
    }

    @Test
    void bindsExplicitValues() {
        runner.withPropertyValues(
                        "eia.auth.enabled=true",
                        "eia.auth.jwks-uri=https://auth.example/oauth2/jwks",
                        "eia.auth.issuer=https://auth.example")
                .run(context -> {
                    AuthProperties properties = context.getBean(AuthProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.jwksUri()).isEqualTo("https://auth.example/oauth2/jwks");
                    assertThat(properties.issuer()).isEqualTo("https://auth.example");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class PropertiesConfiguration {
    }
}
