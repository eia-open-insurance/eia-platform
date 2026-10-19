package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventJson;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

/**
 * HTTP delivery over Spring {@link RestClient}. This is the only transport:
 * there is no broker (ADR-0004 and SPEC 7.5).
 *
 * <p>Headers follow SPEC 7.5.1. A 2xx is DELIVERED, 409/422 are a permanent
 * consumer rejection and become DEAD, and every other response or transport
 * failure is RETRY. The request body is the canonical JSON envelope; the HMAC
 * signature covers {@code timestamp + "." + body}.</p>
 */
public final class RestClientOutboxDispatcher implements OutboxDispatcher {
    public static final String HEADER_EVENT_ID = "X-EIA-Event-Id";
    public static final String HEADER_EVENT_TYPE = "X-EIA-Event-Type";
    public static final String HEADER_EVENT_VERSION = "X-EIA-Event-Version";
    public static final String HEADER_AGGREGATE_VERSION = "X-EIA-Aggregate-Version";
    public static final String HEADER_SIGNATURE = "X-EIA-Signature";
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    private static final int MAX_ERROR_LENGTH = 2000;

    private final RestClient restClient;
    private final String endpoint;
    private final String signingSecret;
    private final Clock clock;

    public RestClientOutboxDispatcher(RestClient restClient, OutboxProperties properties) {
        this(restClient, properties, Clock.systemUTC());
    }

    public RestClientOutboxDispatcher(RestClient restClient, OutboxProperties properties, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        Objects.requireNonNull(properties, "properties");
        this.endpoint = requireEndpoint(properties.getEndpoint());
        this.signingSecret = properties.getSigningSecret() == null ? "" : properties.getSigningSecret();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public OutboxDispatchResult dispatch(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        String body = EventJson.toJson(event);
        long timestamp = clock.instant().getEpochSecond();
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_EVENT_ID, event.eventId().toString())
                    .header(HEADER_EVENT_TYPE, event.eventType())
                    .header(HEADER_EVENT_VERSION, Integer.toString(event.eventVersion()))
                    .header(HEADER_AGGREGATE_VERSION, Long.toString(event.aggregateVersion()))
                    .header(HEADER_IDEMPOTENCY_KEY, event.eventId().toString());
            if (event.correlationId() != null) {
                request.header(HEADER_CORRELATION_ID, event.correlationId().toString());
            }
            if (!signingSecret.isBlank()) {
                request.header(HEADER_SIGNATURE, signatureHeader(signingSecret, timestamp, body));
            }
            request.body(body).retrieve().toBodilessEntity();
            return OutboxDispatchResult.delivered();
        } catch (RestClientResponseException ex) {
            String message = "HTTP " + ex.getStatusCode().value()
                    + (ex.getResponseBodyAsString().isBlank()
                    ? ""
                    : ": " + ex.getResponseBodyAsString());
            int status = ex.getStatusCode().value();
            if (status == 409 || status == 422) {
                return OutboxDispatchResult.dead(truncate(message));
            }
            return OutboxDispatchResult.retry(truncate(message));
        } catch (RestClientException ex) {
            return OutboxDispatchResult.retry(
                    truncate("HTTP transport failure: " + ex.getMessage()));
        }
    }

    /**
     * Builds the default client with the configured timeouts.
     */
    public static RestClient defaultRestClient(OutboxProperties properties) {
        Objects.requireNonNull(properties, "properties");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.effectiveConnectTimeout());
        factory.setReadTimeout(properties.effectiveReadTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * {@code t=<unix>,v1=<hex HMAC-SHA256 of timestamp + "." + body>}.
     */
    public static String signatureHeader(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmacSha256(secret, timestamp + "." + body);
    }

    static String hmacSha256(String secret, String value) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(value, "value");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", ex);
        }
    }

    private static String requireEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "eia.outbox.endpoint must be set when eia.outbox.enabled=true");
        }
        return endpoint;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
