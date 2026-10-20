package com.eia.platform.eventingest.inbox;

/**
 * The {@code X-EIA-Signature} header is missing or does not verify (HTTP 401).
 * The reason is intentionally not echoed to the caller.
 */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException() {
        super("Event signature verification failed");
    }
}
