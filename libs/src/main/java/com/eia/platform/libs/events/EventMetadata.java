package com.eia.platform.libs.events;

/**
 * Envelope metadata. Extra metadata members are not modelled because the
 * canonical contract only defines these two.
 */
public record EventMetadata(String schemaVersion, String sourceBuild) {
    public EventMetadata {
        if (schemaVersion == null || !schemaVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("schemaVersion must be MAJOR.MINOR.PATCH");
        }
        if (sourceBuild != null && sourceBuild.length() > 200) {
            throw new IllegalArgumentException("sourceBuild exceeds 200 characters");
        }
    }

    public static EventMetadata v1(String sourceBuild) {
        return new EventMetadata("1.0.0", sourceBuild);
    }
}
