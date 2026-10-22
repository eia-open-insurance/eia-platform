package com.eia.platform.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Binds an authenticated exchange to the tenant carried by its JWT.
 *
 * <p>After the resource server has authenticated a bearer JWT, this filter
 * reconciles the token claim named by {@code eia.auth.tenant-claim} (default
 * {@code tenant_id}) with the {@code X-EIA-Tenant} request header:
 *
 * <ul>
 *   <li>a missing or blank header is populated from the token claim, so the
 *       downstream service always receives the authenticated tenant;</li>
 *   <li>a header equal to the claim is forwarded untouched;</li>
 *   <li>a header that differs from the claim is rejected with {@code 403} and
 *       an {@code application/problem+json} body whose {@code code} is
 *       {@value #TENANT_FORBIDDEN};</li>
 *   <li>a token without a usable tenant claim is rejected with the same
 *       {@code 403}, because an unbound request must never reach a
 *       tenant-scoped service.</li>
 * </ul>
 *
 * <p>Public paths are excluded because they are not tenant-scoped, and
 * exchanges without a JWT authentication pass through so the authorization
 * filter can produce its usual {@code 401}.
 */
public class TenantHeaderWebFilter implements WebFilter {

    /** Header carrying the tenant context to downstream services. */
    public static final String TENANT_HEADER = "X-EIA-Tenant";

    /** Stable problem code returned when the tenant binding fails. */
    public static final String TENANT_FORBIDDEN = "TENANT_FORBIDDEN";

    private static final String PROBLEM_TYPE = "about:blank";
    private static final String PROBLEM_TITLE = "Forbidden";
    private static final byte[] FALLBACK_PROBLEM =
            ("{\"code\":\"" + TENANT_FORBIDDEN + "\",\"detail\":\"tenant binding rejected\"}")
                    .getBytes(StandardCharsets.UTF_8);

    private final String tenantClaim;
    private final ObjectMapper objectMapper;
    private final List<PathPattern> publicPaths;

    /**
     * @param tenantClaim JWT claim that carries the authenticated tenant
     * @param objectMapper serializer for the problem+json rejection body
     * @param publicPaths exact request paths that are not tenant-scoped and
     *                    therefore bypass the binding check
     */
    public TenantHeaderWebFilter(String tenantClaim, ObjectMapper objectMapper, String... publicPaths) {
        this.tenantClaim = tenantClaim;
        this.objectMapper = objectMapper;
        PathPatternParser parser = new PathPatternParser();
        this.publicPaths = Arrays.stream(publicPaths)
                .map(parser::parse)
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isPublic(exchange)) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .map(jwt -> decide(exchange, jwt))
                .defaultIfEmpty(TenantDecision.allow(exchange))
                .flatMap(decision -> decision.allowed()
                        ? chain.filter(decision.exchange())
                        : forbidden(decision.exchange(), decision.detail()));
    }

    private TenantDecision decide(ServerWebExchange exchange, Jwt jwt) {
        String claimTenant = jwt.getClaimAsString(tenantClaim);
        if (!hasText(claimTenant)) {
            return TenantDecision.deny(exchange,
                    "the authenticated token has no usable " + tenantClaim + " claim");
        }
        String headerTenant = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        if (!hasText(headerTenant)) {
            return TenantDecision.allow(withTenant(exchange, claimTenant));
        }
        if (headerTenant.equals(claimTenant)) {
            return TenantDecision.allow(exchange);
        }
        return TenantDecision.deny(exchange,
                TENANT_HEADER + " does not match the authenticated tenant");
    }

    private boolean isPublic(ServerWebExchange exchange) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        return publicPaths.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = problemBody(exchange, detail);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private byte[] problemBody(ServerWebExchange exchange, String detail) {
        TenantProblem problem = new TenantProblem(
                PROBLEM_TYPE,
                PROBLEM_TITLE,
                HttpStatus.FORBIDDEN.value(),
                detail,
                exchange.getRequest().getPath().value(),
                TENANT_FORBIDDEN);
        try {
            return objectMapper.writeValueAsBytes(problem);
        } catch (JsonProcessingException ex) {
            // The body is a fixed-shape record; serialization can only fail on
            // a programming error, but never let that turn into a 500 while a
            // tenant binding has already been refused.
            return FALLBACK_PROBLEM;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ServerWebExchange withTenant(ServerWebExchange exchange, String tenant) {
        return exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> headers.set(TENANT_HEADER, tenant))
                        .build())
                .build();
    }

    /**
     * Outcome of reconciling the token tenant with the request header.
     */
    private record TenantDecision(ServerWebExchange exchange, boolean allowed, String detail) {

        static TenantDecision allow(ServerWebExchange exchange) {
            return new TenantDecision(exchange, true, null);
        }

        static TenantDecision deny(ServerWebExchange exchange, String detail) {
            return new TenantDecision(exchange, false, detail);
        }
    }

    /**
     * RFC 9457 problem body for a rejected tenant binding.
     */
    private record TenantProblem(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String code) {
    }
}
