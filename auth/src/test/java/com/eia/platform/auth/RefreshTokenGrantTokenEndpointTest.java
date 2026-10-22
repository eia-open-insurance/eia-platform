package com.eia.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the refresh token grant through the real token endpoint, using the
 * refresh token issued by a password grant and HTTP Basic client
 * authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenGrantTokenEndpointTest {

    private static final String TOKEN_ENDPOINT = "/oauth2/token";
    private static final String TENANT_ID = "11111111-1111-7111-8111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void refreshReturnsNewAccessTokenWithOriginalScopes() throws Exception {
        JsonNode initial = passwordToken("eia.read eia.write");
        String refreshToken = initial.get("refresh_token").asText();
        assertThat(refreshToken).isNotBlank();

        JsonNode refreshed = refreshToken(refreshToken, null);

        assertThat(refreshed.get("access_token").asText())
                .isNotBlank()
                .isNotEqualTo(initial.get("access_token").asText());
        assertThat(refreshed.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(refreshed.get("scope").asText())
                .contains("eia.read")
                .contains("eia.write");
        assertThat(scopes(accessTokenClaims(refreshed)))
                .containsExactlyInAnyOrder("eia.read", "eia.write");
    }

    @Test
    void refreshCanNarrowToRequestedScope() throws Exception {
        JsonNode initial = passwordToken("eia.read eia.write");
        String refreshToken = initial.get("refresh_token").asText();

        JsonNode refreshed = refreshToken(refreshToken, "eia.read");

        assertThat(refreshed.get("scope").asText()).isEqualTo("eia.read");
        assertThat(scopes(accessTokenClaims(refreshed))).containsExactly("eia.read");
    }

    @Test
    void refreshedAccessTokenStillCarriesTenantAndRoleClaims() throws Exception {
        JsonNode initial = passwordToken("eia.read eia.write");
        String refreshToken = initial.get("refresh_token").asText();

        JsonNode refreshed = refreshToken(refreshToken, null);

        JsonNode claims = accessTokenClaims(refreshed);
        assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT_ID);
        assertThat(roles(claims)).containsExactly("CLAIMS_ADJUSTER");
    }

    @Test
    void issuesOpaqueRefreshTokenWithoutJwtClaims() throws Exception {
        JsonNode initial = passwordToken("eia.read");

        assertThat(initial.get("refresh_token").asText().split("\\.")).hasSize(1);
    }

    @Test
    void rejectsRefreshScopeOutsideOriginalGrant() throws Exception {
        JsonNode initial = passwordToken("eia.read");
        String refreshToken = initial.get("refresh_token").asText();

        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", refreshToken)
                        .param("scope", "eia.write")
                        .with(httpBasic("eia-shell", "shell-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    private JsonNode passwordToken(String scope) throws Exception {
        MockHttpServletRequestBuilder request = post(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "password")
                .param("username", "adjuster")
                .param("password", "password")
                .param("scope", scope)
                .with(httpBasic("eia-shell", "shell-secret"));
        return readTokenResponse(request);
    }

    private JsonNode refreshToken(String refreshToken, String scope) throws Exception {
        MockHttpServletRequestBuilder request = post(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .with(httpBasic("eia-shell", "shell-secret"));
        if (scope != null) {
            request.param("scope", scope);
        }
        return readTokenResponse(request);
    }

    private JsonNode readTokenResponse(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode accessTokenClaims(JsonNode tokenResponse) throws Exception {
        String accessToken = tokenResponse.get("access_token").asText();
        String[] parts = accessToken.split("\\.");
        assertThat(parts).hasSize(3);
        return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static List<String> scopes(JsonNode claims) {
        List<String> scopes = new ArrayList<>();
        claims.get("scope").forEach(scope -> scopes.add(scope.asText()));
        return scopes;
    }

    private static List<String> roles(JsonNode claims) {
        List<String> roles = new ArrayList<>();
        claims.get("roles").forEach(role -> roles.add(role.asText()));
        return roles;
    }
}
