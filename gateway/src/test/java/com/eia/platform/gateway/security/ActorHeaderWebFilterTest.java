package com.eia.platform.gateway.security;

import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the actor propagation rule: only JWT-authenticated
 * requests get {@code X-EIA-Actor}, and a caller-supplied value always wins.
 */
class ActorHeaderWebFilterTest {

    private static final String SUBJECT = "adjuster";

    private final ActorHeaderWebFilter filter = new ActorHeaderWebFilter();

    @Test
    void addsJwtSubjectWhenActorHeaderIsMissing() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange(), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(SUBJECT)))
                .block();

        assertThat(actorOf(forwarded)).isEqualTo(SUBJECT);
    }

    @Test
    void keepsCallerSuppliedActorHeader() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/parties")
                .header(ActorHeaderWebFilter.ACTOR_HEADER, "upstream-actor")
                .build());

        filter.filter(exchange, capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuthentication(SUBJECT)))
                .block();

        assertThat(actorOf(forwarded)).isEqualTo("upstream-actor");
    }

    @Test
    void leavesRequestUntouchedWithoutAuthentication() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange(), capture(forwarded)).block();

        assertThat(actorOf(forwarded)).isNull();
    }

    @Test
    void leavesRequestUntouchedForNonJwtAuthentication() {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("user", "n/a", List.of());

        filter.filter(exchange(), capture(forwarded))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        assertThat(actorOf(forwarded)).isNull();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/parties").build());
    }

    private static WebFilterChain capture(AtomicReference<ServerWebExchange> forwarded) {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private static String actorOf(AtomicReference<ServerWebExchange> forwarded) {
        ServerWebExchange exchange = forwarded.get();
        assertThat(exchange).isNotNull();
        return exchange.getRequest().getHeaders().getFirst(ActorHeaderWebFilter.ACTOR_HEADER);
    }

    private static JwtAuthenticationToken jwtAuthentication(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt);
    }
}
