package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.EventIngestProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the outbox dispatcher signature:
 * {@code X-EIA-Signature: t=<unix>,v1=<hex HMAC-SHA256 of timestamp + "." + body>}
 * (SPEC 7.5.1).
 *
 * <p>When no secret is configured the header is ignored, which keeps local
 * development usable. Deployments set {@code EIA_EVENT_SIGNING_SECRET} on both
 * the dispatchers and this service.</p>
 */
@Component
public class SignatureVerifier {

    private final String secret;

    public SignatureVerifier(EventIngestProperties properties) {
        this.secret = properties.getSigningSecret() == null ? "" : properties.getSigningSecret().trim();
    }

    public boolean verificationRequired() {
        return !secret.isBlank();
    }

    public void verify(String signatureHeader, byte[] body) {
        if (!verificationRequired()) {
            return;
        }
        if (signatureHeader == null || signatureHeader.isBlank() || body == null) {
            throw new InvalidSignatureException();
        }

        Long timestamp = null;
        String provided = null;
        for (String part : signatureHeader.split(",")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String name = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if ("t".equals(name)) {
                timestamp = parseTimestamp(value);
            } else if ("v1".equals(name)) {
                provided = value;
            }
        }
        if (timestamp == null || provided == null || !isHexSha256(provided)) {
            throw new InvalidSignatureException();
        }

        String expected = hmacSha256(secret, timestamp + "." + new String(body, StandardCharsets.UTF_8));
        byte[] expectedBytes = HexFormat.of().parseHex(expected);
        byte[] providedBytes = HexFormat.of().parseHex(provided.toLowerCase());
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            throw new InvalidSignatureException();
        }
    }

    private static Long parseTimestamp(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isHexSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    static String hmacSha256(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", ex);
        }
    }
}
