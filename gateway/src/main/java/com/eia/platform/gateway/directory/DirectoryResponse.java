package com.eia.platform.gateway.directory;

import java.util.List;

/**
 * Body of {@code GET /api/v1/directory}.
 *
 * @param people people in stable directory order
 * @param teams teams in stable directory order
 */
public record DirectoryResponse(List<DirectoryPerson> people, List<DirectoryTeam> teams) {
}
