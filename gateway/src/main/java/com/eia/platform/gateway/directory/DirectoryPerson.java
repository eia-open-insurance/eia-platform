package com.eia.platform.gateway.directory;

import java.util.List;

/**
 * One person in the shared people and team directory.
 *
 * @param id stable public identifier, for example {@code a-adjuster}
 * @param displayName name shown by the shell
 * @param email contact address used for assignment notifications
 * @param role platform role code, for example {@code CLAIMS_ADJUSTER}
 * @param teams team codes the person belongs to, in directory order
 */
public record DirectoryPerson(
        String id,
        String displayName,
        String email,
        String role,
        List<String> teams) {
}
