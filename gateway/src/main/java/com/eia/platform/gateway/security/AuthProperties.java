package com.eia.platform.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Opt-in edge authentication settings.
 *
 * <p>Authentication is disabled by default so the gateway keeps its historical
 * public behaviour until an environment explicitly opts in with
 * {@code eia.auth.enabled=true}.
 *
 * @param enabled whether bearer JWT authentication is required outside the
 *                public health and directory endpoints
 * @param jwksUri JWK set used to verify JWT signatures
 * @param issuer required {@code iss} claim of accepted JWTs
 * @param tenantClaim JWT claim that carries the tenant bound to every
 *                    authenticated non-public exchange
 */
@ConfigurationProperties(prefix = "eia.auth")
public record AuthProperties(
        boolean enabled,
        @DefaultValue("http://localhost:9000/oauth2/jwks") String jwksUri,
        @DefaultValue("http://localhost:9000") String issuer,
        @DefaultValue("tenant_id") String tenantClaim) {
}
