package com.eia.platform.eventingest.api;

import com.eia.platform.eventingest.inbox.EnvelopeValidationException;
import com.eia.platform.eventingest.inbox.EventConflictException;
import com.eia.platform.eventingest.inbox.InvalidSignatureException;
import com.eia.platform.eventingest.inbox.PayloadTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RFC 9457 error responses with the platform error taxonomy (SPEC 6.11).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERROR_BASE = "https://errors.eia.example/event-ingest/";

    @ExceptionHandler(EnvelopeValidationException.class)
    public ResponseEntity<Problem> invalidEnvelope(
            EnvelopeValidationException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid event envelope",
                ex.issues().size() + " envelope field(s) failed validation",
                ERROR_BASE + "invalid-envelope", "EIA-PLT-4001", "VALIDATION",
                request, ex.issues(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> unreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "request body is not valid JSON",
                ERROR_BASE + "invalid-envelope", "EIA-PLT-4002", "VALIDATION",
                request, null, null);
    }

    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<Problem> invalidSignature(
            InvalidSignatureException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "Event signature verification failed",
                "X-EIA-Signature is missing or invalid for this deployment",
                ERROR_BASE + "invalid-signature", "EIA-PLT-4010", "AUTHENTICATION",
                request, null, null);
    }

    @ExceptionHandler(EventConflictException.class)
    public ResponseEntity<Problem> conflict(
            EventConflictException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Event id already ingested",
                "eventId was already stored with a different payload; events are immutable",
                ERROR_BASE + "event-conflict", "EIA-PLT-4090", "CONFLICT",
                request, null, Map.of("eventId", ex.eventId().toString()));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<Problem> tooLarge(
            PayloadTooLargeException ex, HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Event envelope too large",
                "the maximum event envelope size is 1 MB",
                ERROR_BASE + "payload-too-large", "EIA-PLT-4130", "VALIDATION",
                request, null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Problem> unsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
                "the event envelope must be sent as application/json",
                ERROR_BASE + "unsupported-media-type", "EIA-PLT-4150", "VALIDATION",
                request, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Problem> methodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                "the requested method is not supported for this path",
                ERROR_BASE + "method-not-allowed", "EIA-PLT-4050", "VALIDATION",
                request, null, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> invalidParameter(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid query parameter",
                "query parameter " + ex.getName() + " has an invalid value",
                ERROR_BASE + "invalid-parameter", "EIA-PLT-4003", "VALIDATION",
                request, List.of(new FieldIssue(ex.getName(), "INVALID",
                        ex.getName() + " has an invalid value")), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Problem> notFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found",
                "no endpoint matches the request path",
                ERROR_BASE + "not-found", "EIA-PLT-4040", "NOT_FOUND",
                request, null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> internal(Exception ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        log.error("Unhandled event-ingest failure correlationId={}", correlationId, ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "the request could not be completed",
                ERROR_BASE + "internal", "EIA-PLT-5000", "INTERNAL",
                request, null, null);
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            String code,
            String category,
            HttpServletRequest request,
            List<FieldIssue> errors,
            Map<String, Object> meta) {
        Problem body = new Problem(
                type,
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                code,
                category,
                false,
                correlationId(request),
                errors,
                meta);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        if (header != null && !header.isBlank() && header.length() <= 128) {
            return header.replaceAll("[^A-Za-z0-9._:-]", "");
        }
        return UUID.randomUUID().toString();
    }
}
