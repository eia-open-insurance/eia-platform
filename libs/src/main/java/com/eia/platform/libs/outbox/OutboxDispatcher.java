package com.eia.platform.libs.outbox;

import com.eia.platform.libs.events.EventEnvelope;

/**
 * Delivers one event to the configured subscriber over authenticated HTTP.
 * Implementations must classify permanent and transient failures; they must
 * not throw for an expected delivery failure.
 */
@FunctionalInterface
public interface OutboxDispatcher {
    OutboxDispatchResult dispatch(EventEnvelope event);
}
