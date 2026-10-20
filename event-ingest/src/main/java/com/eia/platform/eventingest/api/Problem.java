package com.eia.platform.eventingest.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * RFC 9457 {@code application/problem+json} body with the platform error
 * extensions described in SPEC 6.11.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String category,
        boolean retryable,
        String correlationId,
        List<FieldIssue> errors,
        Map<String, Object> meta
) {
}
