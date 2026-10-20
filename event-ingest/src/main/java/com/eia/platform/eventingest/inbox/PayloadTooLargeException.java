package com.eia.platform.eventingest.inbox;

/**
 * The request body exceeds the 1 MB JSON limit in SPEC 7.6 (HTTP 413).
 */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(int maxBytes) {
        super("Event envelope exceeds the " + maxBytes + " byte limit");
    }
}
