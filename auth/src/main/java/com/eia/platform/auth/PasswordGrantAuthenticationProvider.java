package com.eia.platform.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.util.Assert;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Issues access and refresh tokens for the resource owner password grant.
 *
 * <p>Spring Authorization Server 1.4 does not ship a password grant provider, so
 * this implementation follows the contract of the built-in grant providers: the
 * client must already be authenticated with a registered client that allows the
 * {@code password} grant, the resource owner is authenticated separately, and
 * the issued token is recorded through the shared authorization service.
 */
public final class PasswordGrantAuthenticationProvider implements AuthenticationProvider {

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-5.2";

    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;
    private final AuthenticationManager authenticationManager;

    public PasswordGrantAuthenticationProvider(
            OAuth2AuthorizationService authorizationService,
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
            AuthenticationManager authenticationManager) {
        Assert.notNull(authorizationService, "authorizationService cannot be null");
        Assert.notNull(tokenGenerator, "tokenGenerator cannot be null");
        Assert.notNull(authenticationManager, "authenticationManager cannot be null");
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
        this.authenticationManager = authenticationManager;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        PasswordGrantAuthenticationToken passwordAuthentication =
                (PasswordGrantAuthenticationToken) authentication;
        OAuth2ClientAuthenticationToken clientPrincipal =
                getAuthenticatedClientElseThrowInvalidClient(passwordAuthentication);
        RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();

        if (!registeredClient.getAuthorizationGrantTypes()
                .contains(PasswordGrantAuthenticationToken.PASSWORD)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT);
        }

        Authentication resourceOwner = authenticateResourceOwner(passwordAuthentication);
        Set<String> authorizedScopes = resolveAuthorizedScopes(passwordAuthentication, registeredClient);

        DefaultOAuth2TokenContext.Builder tokenContextBuilder = DefaultOAuth2TokenContext.builder()
                .registeredClient(registeredClient)
                .principal(resourceOwner)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .authorizationGrantType(PasswordGrantAuthenticationToken.PASSWORD)
                .authorizationGrant(passwordAuthentication);

        OAuth2TokenContext accessTokenContext = tokenContextBuilder
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();
        OAuth2Token generatedAccessToken = this.tokenGenerator.generate(accessTokenContext);
        if (generatedAccessToken == null) {
            throw serverError("The token generator failed to generate the access token.");
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                generatedAccessToken.getTokenValue(),
                generatedAccessToken.getIssuedAt(),
                generatedAccessToken.getExpiresAt(),
                authorizedScopes);

        OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization
                .withRegisteredClient(registeredClient)
                .principalName(resourceOwner.getName())
                .authorizationGrantType(PasswordGrantAuthenticationToken.PASSWORD)
                .authorizedScopes(authorizedScopes)
                .attribute(Principal.class.getName(), resourceOwner);
        if (generatedAccessToken instanceof ClaimAccessor claimAccessor) {
            authorizationBuilder.token(accessToken, metadata -> metadata.put(
                    OAuth2Authorization.Token.CLAIMS_METADATA_NAME, claimAccessor.getClaims()));
        } else {
            authorizationBuilder.accessToken(accessToken);
        }

        OAuth2RefreshToken refreshToken = null;
        if (registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            OAuth2TokenContext refreshTokenContext = tokenContextBuilder
                    .tokenType(OAuth2TokenType.REFRESH_TOKEN)
                    .build();
            OAuth2Token generatedRefreshToken = this.tokenGenerator.generate(refreshTokenContext);
            if (generatedRefreshToken != null) {
                if (!(generatedRefreshToken instanceof OAuth2RefreshToken)) {
                    throw serverError("The token generator failed to generate a valid refresh token.");
                }
                refreshToken = (OAuth2RefreshToken) generatedRefreshToken;
                authorizationBuilder.refreshToken(refreshToken);
            }
        }

        this.authorizationService.save(authorizationBuilder.build());

        Map<String, Object> additionalParameters = Collections.emptyMap();
        return new OAuth2AccessTokenAuthenticationToken(
                registeredClient, clientPrincipal, accessToken, refreshToken, additionalParameters);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PasswordGrantAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private Authentication authenticateResourceOwner(PasswordGrantAuthenticationToken passwordAuthentication) {
        try {
            Authentication resourceOwner = this.authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            passwordAuthentication.getUsername(), passwordAuthentication.getPassword()));
            if (resourceOwner instanceof CredentialsContainer credentialsContainer) {
                credentialsContainer.eraseCredentials();
            }
            return resourceOwner;
        } catch (AuthenticationException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_GRANT, "Invalid resource owner credentials", ERROR_URI), ex);
        }
    }

    private static Set<String> resolveAuthorizedScopes(
            PasswordGrantAuthenticationToken passwordAuthentication, RegisteredClient registeredClient) {
        Set<String> requestedScopes = passwordAuthentication.getScopes();
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            return new LinkedHashSet<>(registeredClient.getScopes());
        }
        for (String requestedScope : requestedScopes) {
            if (!registeredClient.getScopes().contains(requestedScope)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_SCOPE,
                        "Invalid scope: " + requestedScope,
                        ERROR_URI));
            }
        }
        return new LinkedHashSet<>(requestedScopes);
    }

    private static OAuth2ClientAuthenticationToken getAuthenticatedClientElseThrowInvalidClient(
            Authentication authentication) {
        if (authentication.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientPrincipal
                && clientPrincipal.isAuthenticated()
                && clientPrincipal.getRegisteredClient() != null) {
            return clientPrincipal;
        }
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    }

    private static OAuth2AuthenticationException serverError(String description) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR, description, ERROR_URI));
    }
}
