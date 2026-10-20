package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.EventIngestProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureVerifierTest {

    private static final String SECRET = "smoke-secret";
    private static final String BODY_TEXT =
            "{\"eventId\":\"0192f3a4-8f1c-7c3a-9b2e-6a1d4f8e2b90\"}";
    private static final byte[] BODY = BODY_TEXT.getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsAValidSignature() {
        SignatureVerifier verifier = verifier(SECRET);

        assertThatCode(() -> verifier.verify(header(SECRET, 1_767_225_600L, BODY_TEXT), BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSignatureWhenASecretIsConfigured() {
        SignatureVerifier verifier = verifier(SECRET);

        assertThatThrownBy(() -> verifier.verify(null, BODY))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("", BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsMalformedSignatureHeader() {
        SignatureVerifier verifier = verifier(SECRET);

        assertThatThrownBy(() -> verifier.verify("not-a-signature", BODY))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("t=abc,v1=deadbeef", BODY))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("t=123,v1=xyz", BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsASignatureForDifferentContent() {
        SignatureVerifier verifier = verifier(SECRET);

        assertThatThrownBy(() -> verifier.verify(header(SECRET, 123L, "different-body"), BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsASignatureFromAnotherSecret() {
        SignatureVerifier verifier = verifier(SECRET);

        assertThatThrownBy(() -> verifier.verify(header("other-secret", 123L, BODY_TEXT), BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void skipsVerificationWhenNoSecretIsConfigured() {
        SignatureVerifier verifier = verifier("");

        assertThatCode(() -> verifier.verify(null, BODY)).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify("garbage", BODY)).doesNotThrowAnyException();
    }

    private static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + SignatureVerifier.hmacSha256(secret, timestamp + "." + body);
    }

    private static SignatureVerifier verifier(String secret) {
        EventIngestProperties properties = new EventIngestProperties();
        properties.setSigningSecret(secret);
        return new SignatureVerifier(properties);
    }
}
