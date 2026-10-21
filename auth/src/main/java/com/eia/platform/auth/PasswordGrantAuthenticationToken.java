package com.eia.platform.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;

import java.util.Map;
import java.util.Set;

/**
 * Authentication request for an OAuth 2.0 resource owner password grant.
 *
 * <p>The resource owner credentials travel only from the token request to the
 * authentication provider; they are not persisted in the authorization.
 */
public final class PasswordGrantAuthenticationToken extends OAuth2AuthorizationGrantAuthenticationToken {

    private static final long serialVersionUID = 1L;

    /**
     * OAuth 2.0 resource owner password grant type.
     *
     * <p>Spring Security 6.4 deprecates its built-in constant because the grant
     * is deprecated in OAuth 2.1, but it is still needed for local clients. This
     * value compares equal to the built-in constant by grant-type value.
     */
    public static final AuthorizationGrantType PASSWORD = new AuthorizationGrantType("password");

    private final String username;
    private final String password;
    private final Set<String> scopes;

    public PasswordGrantAuthenticationToken(
            String username,
            String password,
            Set<String> scopes,
            Authentication clientPrincipal,
            Map<String, Object> additionalParameters) {
        super(PASSWORD, clientPrincipal, additionalParameters);
        this.username = username;
        this.password = password;
        this.scopes = scopes;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    public Set<String> getScopes() {
        return this.scopes;
    }
}
