package com.eia.platform.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtGrantedAuthoritiesConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Edge security for the reactive gateway.
 *
 * <p>JWT validation is opt-in through {@code eia.auth.enabled}. When disabled
 * (the default) every exchange is permitted, preserving the gateway's public
 * behaviour. When enabled, only health and the public directory stay open and
 * every other exchange must carry a valid bearer JWT issued by
 * {@code eia.auth.issuer} and signed by a key from {@code eia.auth.jwks-uri}.
 * Once authenticated, API exchanges are authorized by HTTP method: safe
 * methods need {@code SCOPE_eia.read} and mutations need
 * {@code SCOPE_eia.write}. Authenticated JWT requests are then bound to the
 * token's tenant claim: a missing {@code X-EIA-Tenant} header is populated from
 * the claim, a conflicting header is rejected with {@code 403}, and the token
 * subject is forwarded as {@code X-EIA-Actor}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class GatewaySecurityConfig {

    /** Paths that stay public even when edge authentication is enabled. */
    static final String[] PUBLIC_PATHS = { "/actuator/health", "/api/v1/directory" };

    /** Proxied API surface governed by method-level scopes. */
    static final String API_PATHS = "/api/**";

    static final String READ_SCOPE_AUTHORITY = "SCOPE_eia.read";
    static final String WRITE_SCOPE_AUTHORITY = "SCOPE_eia.write";

    @Bean
    @ConditionalOnProperty(prefix = "eia.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    SecurityWebFilterChain disabledSecurityWebFilterChain(ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "eia.auth", name = "enabled", havingValue = "true")
    SecurityWebFilterChain enabledSecurityWebFilterChain(ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter,
            AuthProperties properties,
            ObjectMapper objectMapper) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .pathMatchers(HttpMethod.GET, API_PATHS).hasAuthority(READ_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.HEAD, API_PATHS).hasAuthority(READ_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.OPTIONS, API_PATHS).hasAuthority(READ_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.POST, API_PATHS).hasAuthority(WRITE_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.PUT, API_PATHS).hasAuthority(WRITE_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.PATCH, API_PATHS).hasAuthority(WRITE_SCOPE_AUTHORITY)
                        .pathMatchers(HttpMethod.DELETE, API_PATHS).hasAuthority(WRITE_SCOPE_AUTHORITY)
                        .pathMatchers(API_PATHS).authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // Both filters run directly after authentication. Tenant
                // binding is registered first so a mismatched request is
                // rejected before any downstream identity header is derived.
                .addFilterAfter(new TenantHeaderWebFilter(properties.tenantClaim(), objectMapper, PUBLIC_PATHS),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(new ActorHeaderWebFilter(), SecurityWebFiltersOrder.AUTHENTICATION);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "eia.auth", name = "enabled", havingValue = "true")
    ReactiveJwtDecoder jwtDecoder(AuthProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.jwksUri())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(prefix = "eia.auth", name = "enabled", havingValue = "true")
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new ReactiveJwtGrantedAuthoritiesConverterAdapter(new JwtGrantedAuthoritiesConverter()));
        return converter;
    }
}
