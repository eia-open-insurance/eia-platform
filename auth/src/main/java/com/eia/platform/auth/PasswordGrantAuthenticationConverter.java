package com.eia.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts a {@code grant_type=password} token request into a
 * {@link PasswordGrantAuthenticationToken} for the token endpoint.
 */
public final class PasswordGrantAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
        if (!PasswordGrantAuthenticationToken.PASSWORD.getValue().equals(grantType)) {
            return null;
        }

        Authentication clientPrincipal = SecurityContextHolder.getContext().getAuthentication();
        if (clientPrincipal == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }

        String username = request.getParameter(OAuth2ParameterNames.USERNAME);
        String password = request.getParameter(OAuth2ParameterNames.PASSWORD);
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }

        Set<String> scopes = null;
        String[] scopeParameters = request.getParameterValues(OAuth2ParameterNames.SCOPE);
        if (scopeParameters != null && scopeParameters.length > 0) {
            scopes = new LinkedHashSet<>();
            for (String scopeParameter : scopeParameters) {
                if (!StringUtils.hasText(scopeParameter)) {
                    continue;
                }
                for (String scope : StringUtils.delimitedListToStringArray(scopeParameter, " ")) {
                    if (StringUtils.hasText(scope)) {
                        scopes.add(scope);
                    }
                }
            }
        }

        Map<String, Object> additionalParameters = new HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (OAuth2ParameterNames.GRANT_TYPE.equals(name)
                    || OAuth2ParameterNames.USERNAME.equals(name)
                    || OAuth2ParameterNames.PASSWORD.equals(name)
                    || OAuth2ParameterNames.SCOPE.equals(name)
                    || OAuth2ParameterNames.CLIENT_SECRET.equals(name)) {
                return;
            }
            additionalParameters.put(name, values.length == 1 ? values[0] : Arrays.asList(values));
        });

        return new PasswordGrantAuthenticationToken(
                username, password, scopes, clientPrincipal, additionalParameters);
    }
}
