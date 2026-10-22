package com.eia.platform.gateway.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebHandler;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Verifies the opt-in behaviour: when {@code eia.auth.enabled=true}, health and
 * the directory stay public while every other exchange needs a valid bearer
 * JWT. API exchanges additionally require {@code SCOPE_eia.read} for safe
 * methods and {@code SCOPE_eia.write} for mutations. An authenticated JWT
 * request is bound to the token tenant claim: the {@code X-EIA-Tenant} header
 * is injected or must match, and the subject is carried downstream as
 * {@code X-EIA-Actor}.
 *
 * <p>The production JWKS decoder is replaced by a mock that delegates to a real
 * Nimbus decoder keyed from a generated RSA pair, so signature and issuer
 * validation both run without a network call to the auth service. Exchange
 * authorization tests bind the real security filter chain to a stub handler so
 * assertions fail on security decisions rather than on downstream availability.
 */
@SpringBootTest(properties = "eia.auth.enabled=true", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityEnabledTest {

    private static final String ISSUER = "http://localhost:9000";
    private static final String SUBJECT = "adjuster";
    private static final String READ_SCOPE = "eia.read";
    private static final String WRITE_SCOPE = "eia.write";
    private static final String SCOPE = READ_SCOPE + " " + WRITE_SCOPE;
    private static final String TENANT_CLAIM = "tenant_id";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final RSAKey SIGNING_KEY = rsaKey(KEY_PAIR);
    private static final JwtEncoder ENCODER = encoder(SIGNING_KEY);
    private static final RSAKey OTHER_SIGNING_KEY = rsaKey(generateKeyPair());
    private static final JwtEncoder OTHER_ENCODER = encoder(OTHER_SIGNING_KEY);
    private static final NimbusReactiveJwtDecoder REAL_DECODER = realDecoder();

    @Autowired
    private WebTestClient client;

    @Autowired
    private SecurityWebFilterChain securityWebFilterChain;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter;

    @MockitoBean(enforceOverride = true)
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void delegateDecodingToRealNimbusDecoder() {
        lenient().when(jwtDecoder.decode(anyString()))
                .thenAnswer(invocation -> REAL_DECODER.decode(invocation.getArgument(0)));
    }

    @Test
    void exposesSecurityFilterChainBeanAndEnabledProperties() {
        assertThat(securityWebFilterChain).isNotNull();
        assertThat(authProperties.enabled()).isTrue();
        assertThat(authProperties.jwksUri()).isEqualTo("http://localhost:9000/oauth2/jwks");
        assertThat(authProperties.issuer()).isEqualTo(ISSUER);
        assertThat(authProperties.tenantClaim()).isEqualTo(TENANT_CLAIM);
    }

    @Test
    void rejectsMissingTokenForProtectedRoute() {
        client.get().uri("/api/v1/parties").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*");
    }

    @Test
    void keepsHealthAndDirectoryPublic() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk();
        client.get().uri("/api/v1/directory").exchange()
                .expectStatus().isOk();
    }

    @Test
    void acceptsValidSignedJwt() {
        // A path without a gateway route still proves authentication succeeded:
        // the request reaches routing and gets a 404 instead of a 401.
        client.get().uri("/not-a-route")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(ENCODER, SIGNING_KEY, ISSUER)))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void rejectsTokenSignedByUntrustedKey() {
        client.get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(OTHER_ENCODER, OTHER_SIGNING_KEY, ISSUER)))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenFromUntrustedIssuer() {
        client.get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(token(ENCODER, SIGNING_KEY, "http://evil.example")))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void mapsScopeClaimToAuthorities() {
        Jwt jwt = REAL_DECODER.decode(token(ENCODER, SIGNING_KEY, ISSUER)).block();
        assertThat(jwt).isNotNull();

        AbstractAuthenticationToken authentication = jwtAuthenticationConverter.convert(jwt).block();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_eia.read", "SCOPE_eia.write");
        assertThat(authentication.getName()).isEqualTo(SUBJECT);
    }

    @Test
    void allowsReadScopeOnGet() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(readToken()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void allowsReadScopeOnHeadAndOptions() {
        WebTestClient filteredClient = filteredClient(okHandler());
        String authorization = bearer(readToken());

        filteredClient.head().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange()
                .expectStatus().isOk();
        filteredClient.options().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void forbidsReadScopeOnMutatingMethods() {
        WebTestClient filteredClient = filteredClient(okHandler());
        String authorization = bearer(readToken());

        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            filteredClient.method(method).uri("/api/v1/parties")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Test
    void allowsWriteScopeOnPost() {
        filteredClient(okHandler()).post().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(writeToken()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void writeScopeAloneDoesNotGrantRead() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(writeToken()))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void forbidsTokenWithoutRequiredScope() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithoutScope()))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void propagatesJwtSubjectAsActorHeader() {
        AtomicReference<String> actor = new AtomicReference<>();

        filteredClient(captureActor(actor)).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(readToken()))
                .exchange()
                .expectStatus().isOk();

        assertThat(actor).hasValue(SUBJECT);
    }

    @Test
    void doesNotOverwriteCallerSuppliedActorHeader() {
        AtomicReference<String> actor = new AtomicReference<>();

        filteredClient(captureActor(actor)).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(readToken()))
                .header(ActorHeaderWebFilter.ACTOR_HEADER, "upstream-actor")
                .exchange()
                .expectStatus().isOk();

        assertThat(actor).hasValue("upstream-actor");
    }

    @Test
    void allowsRequestWhoseTenantHeaderMatchesTheToken() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(readToken()))
                .header(TenantHeaderWebFilter.TENANT_HEADER, TENANT)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void forbidsRequestWhoseTenantHeaderDiffersFromTheToken() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(readToken()))
                .header(TenantHeaderWebFilter.TENANT_HEADER, OTHER_TENANT)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(TenantHeaderWebFilter.TENANT_FORBIDDEN)
                .jsonPath("$.detail").isNotEmpty();
    }

    @Test
    void injectsTenantHeaderFromTokenWhenCallerSuppliedNone() {
        AtomicReference<String> tenant = new AtomicReference<>();

        filteredClient(captureTenant(tenant)).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(readToken()))
                .exchange()
                .expectStatus().isOk();

        assertThat(tenant).hasValue(TENANT);
    }

    @Test
    void forbidsTokenWithoutTenantClaim() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithoutTenantClaim()))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(TenantHeaderWebFilter.TENANT_FORBIDDEN)
                .jsonPath("$.detail").isNotEmpty();
    }

    @Test
    void forbidsTokenWithBlankTenantClaim() {
        filteredClient(okHandler()).get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithBlankTenantClaim()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo(TenantHeaderWebFilter.TENANT_FORBIDDEN);
    }

    @Test
    void keepsPublicPathsOpenForTokenWithoutTenantClaim() {
        client.get().uri("/api/v1/directory")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenWithoutTenantClaim()))
                .exchange()
                .expectStatus().isOk();
    }

    private WebTestClient filteredClient(WebHandler handler) {
        return WebTestClient.bindToWebHandler(handler)
                .webFilter(new WebFilterChainProxy(securityWebFilterChain))
                .build();
    }

    private static WebHandler okHandler() {
        return exchange -> ServerResponse.ok().build().then();
    }

    private static WebHandler captureActor(AtomicReference<String> actor) {
        return exchange -> {
            actor.set(exchange.getRequest().getHeaders().getFirst(ActorHeaderWebFilter.ACTOR_HEADER));
            return ServerResponse.ok().build().then();
        };
    }

    private static WebHandler captureTenant(AtomicReference<String> tenant) {
        return exchange -> {
            tenant.set(exchange.getRequest().getHeaders().getFirst(TenantHeaderWebFilter.TENANT_HEADER));
            return ServerResponse.ok().build().then();
        };
    }

    private static String readToken() {
        return token(ENCODER, SIGNING_KEY, ISSUER, SUBJECT, READ_SCOPE);
    }

    private static String writeToken() {
        return token(ENCODER, SIGNING_KEY, ISSUER, SUBJECT, WRITE_SCOPE);
    }

    private static String tokenWithoutScope() {
        return token(ENCODER, SIGNING_KEY, ISSUER, SUBJECT, null);
    }

    private static String tokenWithoutTenantClaim() {
        return token(ENCODER, SIGNING_KEY, ISSUER, SUBJECT, READ_SCOPE, null);
    }

    private static String tokenWithBlankTenantClaim() {
        return token(ENCODER, SIGNING_KEY, ISSUER, SUBJECT, READ_SCOPE, "   ");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String token(JwtEncoder encoder, RSAKey signingKey, String issuer) {
        return token(encoder, signingKey, issuer, SUBJECT, SCOPE, TENANT);
    }

    private static String token(JwtEncoder encoder, RSAKey signingKey, String issuer, String subject, String scope) {
        return token(encoder, signingKey, issuer, subject, scope, TENANT);
    }

    private static String token(JwtEncoder encoder, RSAKey signingKey, String issuer,
            String subject, String scope, String tenant) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        if (scope != null) {
            claims.claim("scope", scope);
        }
        if (tenant != null) {
            claims.claim(TENANT_CLAIM, tenant);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.getKeyID())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static NimbusReactiveJwtDecoder realDecoder() {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA is not available", ex);
        }
    }

    private static RSAKey rsaKey(KeyPair keyPair) {
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    private static JwtEncoder encoder(RSAKey signingKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }
}
