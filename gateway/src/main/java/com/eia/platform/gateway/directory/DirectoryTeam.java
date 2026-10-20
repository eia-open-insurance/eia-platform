package com.eia.platform.gateway.directory;

/**
 * One team in the shared people and team directory.
 *
 * @param code stable team code, for example {@code DIRECT_CLAIMS}
 * @param label human-readable team name
 */
public record DirectoryTeam(String code, String label) {
}
