package com.eia.platform.eventingest.inbox;

import com.eia.platform.eventingest.api.FieldIssue;

import java.util.List;

/**
 * The request body is not a valid canonical event envelope (HTTP 400).
 */
public class EnvelopeValidationException extends RuntimeException {

    private final transient List<FieldIssue> issues;

    public EnvelopeValidationException(List<FieldIssue> issues) {
        super("Invalid event envelope: " + issues.size() + " field(s)");
        this.issues = List.copyOf(issues);
    }

    public List<FieldIssue> issues() {
        return issues;
    }
}
