package com.eia.platform.gateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the tenant binding rule: the token tenant is injected when
 * the caller omits the header, a conflicting header is refused with a
 * problem+json 403, and a token without a tenant claim fails closed unless the
 * path is public.
 */
class TenantHeaderWebFilterTest {

    private static final String TENANT_CLAIM = "tenant_id";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "adjuster";
    private static final String PUBLIC_PATH = "/api/v1/directory";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TenantHeaderWebFilter filter =
            new TenantHeaderWebFilter(TENANT_CLAIM, OBJECT_MAPPER, PUBLIC_PATH);

    @Test
    void injectsTokenTenantWhenHeaderIsMissing() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange(), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(TENANT)))
                .block();

        assertThat(tenantOf(forwarded)).isEqualTo(TENANT);
    }

    @Test
    void treatsBlankHeaderAsMissingAndInjectsTokenTenant() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchangeWithHeader("   "), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(TENANT)))
                .block();

        assertThat(tenantOf(forwarded)).isEqualTo(TENANT);
    }

    @Test
    void forwardsMatchingTenantHeaderUntouched() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchangeWithHeader(TENANT), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(TENANT)))
                .block();

        assertThat(tenantOf(forwarded)).isEqualTo(TENANT);
    }

    @Test
    void rejectsMismatchedTenantHeader() {
        MockServerWebExchange exchange = exchangeWithHeader(OTHER_TENANT);
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, markAndContinue(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(TENANT)))
                .block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        JsonNode problem = problemBody(exchange);
        assertThat(problem.path("code").asText()).isEqualTo(TenantHeaderWebFilter.TENANT_FORBIDDEN);
        assertThat(problem.path("detail").asText()).contains(TenantHeaderWebFilter.TENANT_HEADER);
        assertThat(problem.path("status").asInt()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.path("instance").asText()).isEqualTo("/api/v1/parties");
    }

    @Test
    void rejectsTokenWithoutTenantClaim() {
        MockServerWebExchange exchange = exchange();
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, markAndContinue(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(null)))
                .block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode problem = problemBody(exchange);
        assertThat(problem.path("code").asText()).isEqualTo(TenantHeaderWebFilter.TENANT_FORBIDDEN);
        assertThat(problem.path("detail").asText()).contains(TENANT_CLAIM);
    }

    @Test
    void rejectsBlankTenantClaim() {
        MockServerWebExchange exchange = exchange();
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.filter(exchange, markAndContinue(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication("   ")))
                .block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(problemBody(exchange).path("code").asText())
                .isEqualTo(TenantHeaderWebFilter.TENANT_FORBIDDEN);
    }

    @Test
    void letsPublicPathsPassWithoutTenantClaim() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange(MockServerHttpRequest.get(PUBLIC_PATH)), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(null)))
                .block();

        assertThat(forwarded).isNotNull();
        assertThat(tenantOf(forwarded)).isNull();
    }

    @Test
    void passesThroughWithoutAuthentication() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange(), capture(forwarded)).block();

        assertThat(forwarded).isNotNull();
        assertThat(tenantOf(forwarded)).isNull();
    }

    @Test
    void passesThroughForNonJwtAuthentication() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("user", "n/a", List.of());

        filter.filter(exchange(), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertThat(forwarded).isNotNull();
        assertThat(tenantOf(forwarded)).isNull();
    }

    private static MockServerWebExchange exchange() {
        return exchange(MockServerHttpRequest.get("/api/v1/parties"));
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }

    private static MockServerWebExchange exchangeWithHeader(String tenant) {
        return exchange(MockServerHttpRequest
                .get("/api/v1/parties")
                .header(TenantHeaderWebFilter.TENANT_HEADER, tenant));
    }

    private static WebFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private static WebFilterChain markAndContinue(AtomicBoolean forwarded) {
        return exchange -> {
            forwarded.set(true);
            return Mono.empty();
        };
    }

    private static String tenantOf(AtomicReference<ServerWebExchange> forwarded) {
        ServerWebExchange exchange = forwarded.get();
        assertThat(exchange).isNotNull();
        return exchange.getRequest().getHeaders().getFirst(TenantHeaderWebFilter.TENANT_HEADER);
    }

    private static JsonNode problemBody(MockServerWebExchange exchange) {
        try {
            return OBJECT_MAPPER.readTree(exchange.getResponse().getBodyAsString().block());
        } catch (IOException ex) {
            throw new IllegalStateException("problem body is not valid JSON", ex);
        }
    }

    private static JwtAuthenticationToken jwtAuthentication(String tenant) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (tenant != null) {
            builder.claim(TENANT_CLAIM, tenant);
        }
        return new JwtAuthenticationToken(builder.build());
    }
}
