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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Verifies the opt-in behaviour: when {@code eia.auth.enabled=true}, health and
 * the directory stay public while every other exchange needs a valid bearer
 * JWT. The production JWKS decoder is replaced by a mock that delegates to a
 * real Nimbus decoder keyed from a generated RSA pair, so signature and issuer
 * validation both run without a network call to the auth service.
 */
@SpringBootTest(properties = "eia.auth.enabled=true", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewaySecurityEnabledTest {

    private static final String ISSUER = "http://localhost:9000";
    private static final String SCOPE = "eia.read eia.write";

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
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ENCODER, SIGNING_KEY, ISSUER))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void rejectsTokenSignedByUntrustedKey() {
        client.get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(OTHER_ENCODER, OTHER_SIGNING_KEY, ISSUER))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsTokenFromUntrustedIssuer() {
        client.get().uri("/api/v1/parties")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ENCODER, SIGNING_KEY, "http://evil.example"))
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
        assertThat(authentication.getName()).isEqualTo("adjuster");
    }

    private static String token(JwtEncoder encoder, RSAKey signingKey, String issuer) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("adjuster")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("scope", SCOPE)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.getKeyID())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
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
