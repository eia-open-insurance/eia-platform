/**
 * Event ingest / transactional-inbox service for the EIA platform.
 *
 * <p>The service is deliberately transport-simple: expert services deliver
 * canonical events over authenticated HTTP from their transactional outbox
 * (SPEC 7.5), and this service persists them exactly once by {@code eventId}.
 * It owns no business aggregate and publishes no events.</p>
 */
package com.eia.platform.eventingest;
