package com.eia.platform.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the local password grant through the real token endpoint, including
 * client authentication with HTTP Basic, scope handling, JWT access token
 * claims, and JWKS publication.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordGrantTokenEndpointTest {

    private static final String ISSUER = "http://localhost:9000";
    private static final String TENANT_ID = "11111111-1111-7111-8111-111111111111";
    private static final String TOKEN_ENDPOINT = "/oauth2/token";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void issuesJwtAccessTokenForValidAdjusterPassword() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("username", "adjuster")
                        .param("password", "password")
                        .param("scope", "profile eia.read eia.write")
                        .with(httpBasic("eia-shell", "shell-secret")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.refresh_token").isString())
                .andExpect(jsonPath("$.scope", containsString("eia.write")))
                .andExpect(jsonPath("$.scope", containsString("profile")))
                .andExpect(jsonPath("$.scope", containsString("eia.read")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(responseBody).get("access_token").asText();

        String[] parts = accessToken.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.get("sub").asText()).isEqualTo("adjuster");
        assertThat(claims.get("exp").asLong()).isPositive();
        assertThat(claims.get("scope")).isNotNull();
        assertThat(scopes(claims)).containsExactlyInAnyOrder("profile", "eia.read", "eia.write");
        assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT_ID);
        assertThat(roles(claims)).containsExactly("CLAIMS_ADJUSTER");
    }

    @Test
    void grantsReadAndWriteScopesWhenBothRequested() throws Exception {
        JsonNode tokenResponse = requestPasswordGrant("adjuster", "password", "eia.read eia.write");

        assertThat(tokenResponse.get("scope").asText())
                .contains("eia.read")
                .contains("eia.write");
        assertThat(scopes(accessTokenClaims(tokenResponse)))
                .containsExactlyInAnyOrder("eia.read", "eia.write");
    }

    @Test
    void grantsReadScopeWhenOnlyReadRequested() throws Exception {
        JsonNode tokenResponse = requestPasswordGrant("adjuster", "password", "eia.read");

        assertThat(tokenResponse.get("scope").asText()).isEqualTo("eia.read");
        assertThat(scopes(accessTokenClaims(tokenResponse))).containsExactly("eia.read");
    }

    @Test
    void defaultsAuditorToReadOnlyScopes() throws Exception {
        JsonNode tokenResponse = requestPasswordGrant("auditor", "password", null);

        assertThat(tokenResponse.get("scope").asText())
                .contains("profile")
                .contains("eia.read")
                .doesNotContain("eia.write");
        assertThat(scopes(accessTokenClaims(tokenResponse)))
                .containsExactlyInAnyOrder("profile", "eia.read");
    }

    @Test
    void allowsAuditorToRequestReadScope() throws Exception {
        JsonNode tokenResponse = requestPasswordGrant("auditor", "password", "eia.read");

        assertThat(tokenResponse.get("scope").asText()).isEqualTo("eia.read");
        assertThat(scopes(accessTokenClaims(tokenResponse))).containsExactly("eia.read");
    }

    @Test
    void issuesAuditorAccessTokenWithTenantAndAuditorRole() throws Exception {
        JsonNode claims = accessTokenClaims(requestPasswordGrant("auditor", "password", "eia.read"));

        assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT_ID);
        assertThat(roles(claims)).containsExactly("AUDITOR");
    }

    @Test
    void rejectsAuditorWriteScope() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("username", "auditor")
                        .param("password", "password")
                        .param("scope", "eia.read eia.write")
                        .with(httpBasic("eia-shell", "shell-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void rejectsInvalidPasswordWithoutIssuingAToken() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("username", "adjuster")
                        .param("password", "wrong-password")
                        .with(httpBasic("eia-shell", "shell-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void rejectsUnregisteredScope() throws Exception {
        mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("username", "adjuster")
                        .param("password", "password")
                        .param("scope", "eia.read eia.admin")
                        .with(httpBasic("eia-shell", "shell-secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void publishesSigningKeysForJwtValidation() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").isString())
                .andExpect(jsonPath("$.keys[0].n").isString());
    }

    private JsonNode requestPasswordGrant(String username, String password, String scope) throws Exception {
        MockHttpServletRequestBuilder request = post(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "password")
                .param("username", username)
                .param("password", password)
                .with(httpBasic("eia-shell", "shell-secret"));
        if (scope != null) {
            request.param("scope", scope);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
