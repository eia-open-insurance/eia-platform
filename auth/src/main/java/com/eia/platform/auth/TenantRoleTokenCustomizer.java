package com.eia.platform.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Adds the platform tenancy and role claims to JWT access tokens.
 *
 * <p>The access token is scoped to the configured tenant ({@code tenant_id})
 * and identifies the resource owner's platform roles ({@code roles}), so a
 * transparent gateway can bind a request to the tenant in the token. Roles are
 * emitted as the Spring Security authorities without the {@code ROLE_} prefix
 * used by role-based access checks, for example {@code CLAIMS_ADJUSTER} and
 * {@code AUDITOR}.
 *
 * <p>Only access tokens are customized. ID tokens keep their OpenID Connect
 * claim set, and refresh tokens are opaque values that never pass through a
 * {@link JwtEncodingContext}. The refresh token grant rebuilds the access
 * token context from the stored authorization principal, so a refreshed access
 * token is issued with the same tenant and role claims.
 */
public final class TenantRoleTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    /** Claim holding the tenant the access token is scoped to. */
    public static final String TENANT_ID_CLAIM = "tenant_id";

    /** Claim holding the resource owner's platform roles. */
    public static final String ROLES_CLAIM = "roles";

    private static final String ROLE_PREFIX = "ROLE_";

    private final String tenantId;

    public TenantRoleTokenCustomizer(String tenantId) {
        Assert.hasText(tenantId, "tenantId cannot be empty");
        this.tenantId = tenantId;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        context.getClaims().claim(TENANT_ID_CLAIM, this.tenantId);
        context.getClaims().claim(ROLES_CLAIM, roles(context.getPrincipal()));
    }

    private static List<String> roles(Authentication principal) {
        if (principal == null) {
            return List.of();
        }
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(TenantRoleTokenCustomizer::roleName)
                .sorted()
                .toList();
    }

    private static String roleName(String authority) {
        return authority.startsWith(ROLE_PREFIX)
                ? authority.substring(ROLE_PREFIX.length())
                : authority;
    }
}
