package com.eia.platform.eventingest.api;

/**
 * Raised when a collection route receives a {@code limit} outside its
 * supported range.
 */
public class InvalidLimitException extends RuntimeException {

    private final int limit;
    private final int maxLimit;

    public InvalidLimitException(int limit, int maxLimit) {
        super("limit must be between 1 and " + maxLimit);
        this.limit = limit;
        this.maxLimit = maxLimit;
    }

    public int limit() {
        return limit;
    }

    public int maxLimit() {
        return maxLimit;
    }
}
