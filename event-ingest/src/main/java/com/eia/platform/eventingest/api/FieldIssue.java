package com.eia.platform.eventingest.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One field-level validation issue in an RFC 9457 problem response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldIssue(String field, String code, String message) {
}
