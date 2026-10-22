package com.eia.platform.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebHandler;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the default, opt-out behaviour: the gateway stays public unless an
 * environment explicitly enables edge authentication.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityDisabledTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private SecurityWebFilterChain securityWebFilterChain;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void exposesSecurityFilterChainBean() {
        assertThat(securityWebFilterChain).isNotNull();
    }

    @Test
    void authenticationIsDisabledWithDocumentedDefaults() {
        assertThat(authProperties.enabled()).isFalse();
        assertThat(authProperties.jwksUri()).isEqualTo("http://localhost:9000/oauth2/jwks");
        assertThat(authProperties.issuer()).isEqualTo("http://localhost:9000");
    }

    @Test
    void permitsDirectoryWithoutToken() {
        client.get().uri("/api/v1/directory").exchange()
                .expectStatus().isOk();
    }

    @Test
    void permitsHealthWithoutToken() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk();
    }

    @Test
    void permitsNonPublicExchangeWithoutTokenWhenDisabled() {
        // The mock handler stands in for a route target so the assertion proves
        // authorization, not the availability of a downstream service.
        WebTestClient filteredClient = WebTestClient
                .bindToWebHandler(okHandler())
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .build();

        filteredClient.get().uri("/api/v1/parties").exchange()
                .expectStatus().isOk();
    }

    @Test
    void permitsConflictingTenantHeaderWhenDisabled() {
        WebTestClient filteredClient = WebTestClient
                .bindToWebHandler(okHandler())
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .build();

        filteredClient.get().uri("/api/v1/parties")
                .header(TenantHeaderWebFilter.TENANT_HEADER, "caller-tenant")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ignored-when-authentication-is-disabled")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void doesNotInjectTenantHeaderWhenDisabled() {
        // The tenant binding filter is only installed when authentication is
        // enabled, so the caller's missing header stays missing.
        AtomicReference<String> tenant = new AtomicReference<>();
        WebTestClient filteredClient = WebTestClient
                .bindToWebHandler(exchange -> {
                    tenant.set(exchange.getRequest().getHeaders().getFirst(TenantHeaderWebFilter.TENANT_HEADER));
                    return ServerResponse.ok().build().then();
                })
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .build();

        filteredClient.get().uri("/api/v1/parties").exchange()
                .expectStatus().isOk();

        assertThat(tenant.get()).isNull();
    }

    @Test
    void doesNotPropagateActorHeaderWhenDisabled() {
        // No JWT decoding and no actor filter are installed in the disabled
        // configuration, so an Authorization header has no effect at all.
        AtomicReference<String> actor = new AtomicReference<>();
        WebTestClient filteredClient = WebTestClient
                .bindToWebHandler(exchange -> {
                    actor.set(exchange.getRequest().getHeaders().getFirst(ActorHeaderWebFilter.ACTOR_HEADER));
                    return ServerResponse.ok().build().then();
                })
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .build();

        filteredClient.get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ignored-when-authentication-is-disabled")
                .exchange()
                .expectStatus().isOk();

        assertThat(actor.get()).isNull();
    }

    private WebHandler okHandler() {
        return exchange -> ServerResponse.ok().build().then();
    }
}
