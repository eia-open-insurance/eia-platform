package com.eia.platform.gateway.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Propagates the authenticated JWT subject to downstream services.
 *
 * <p>When a request is authenticated with a bearer JWT and the caller did not
 * already supply an {@code X-EIA-Actor} header, this filter adds that header
 * with the JWT {@code sub} claim as its value. An existing header is never
 * overwritten, so an explicit caller identity survives untouched. Requests
 * without a JWT authentication, and deployments with edge authentication
 * disabled, pass through unchanged.
 */
public class ActorHeaderWebFilter implements WebFilter {

    /** Header carrying the acting person or service to downstream services. */
    public static final String ACTOR_HEADER = "X-EIA-Actor";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getHeaders().containsKey(ACTOR_HEADER)) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .map(Jwt::getSubject)
                .filter(ActorHeaderWebFilter::hasText)
                .map(subject -> withActor(exchange, subject))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ServerWebExchange withActor(ServerWebExchange exchange, String subject) {
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> headers.set(ACTOR_HEADER, subject))
                        .build())
                .build();
    }
}
