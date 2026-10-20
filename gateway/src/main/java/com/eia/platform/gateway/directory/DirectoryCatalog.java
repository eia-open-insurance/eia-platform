package com.eia.platform.gateway.directory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Canonical people and team directory exposed by {@link DirectoryController}.
 *
 * <p>The declaration order is the API order. Append entries without reordering
 * the existing ones so that clients always observe the same stable lists.
 */
@Component
public class DirectoryCatalog {

    private static final List<DirectoryTeam> TEAMS = List.of(
            new DirectoryTeam("DIRECT_CLAIMS", "Direct claims"),
            new DirectoryTeam("ASSUMED_CLAIMS", "Assumed claims"),
            new DirectoryTeam("MAJOR_LOSS", "Major loss"),
            new DirectoryTeam("LITIGATION", "Litigation"),
            new DirectoryTeam("RECOVERY", "Recovery"),
            new DirectoryTeam("SUPERVISORY", "Supervisory"));

    private static final List<DirectoryPerson> PEOPLE = List.of(
            new DirectoryPerson("a-adjuster", "A. Adjuster", "a.adjuster@eia.example",
                    "CLAIMS_ADJUSTER", List.of("DIRECT_CLAIMS", "MAJOR_LOSS")),
            new DirectoryPerson("b-examiner", "B. Examiner", "b.examiner@eia.example",
                    "CLAIMS_EXAMINER", List.of("ASSUMED_CLAIMS", "RECOVERY")),
            new DirectoryPerson("c-manager", "C. Manager", "c.manager@eia.example",
                    "CLAIMS_MANAGER", List.of("SUPERVISORY", "LITIGATION")),
            new DirectoryPerson("d-specialist", "D. Specialist", "d.specialist@eia.example",
                    "MAJOR_LOSS_SPECIALIST", List.of("MAJOR_LOSS", "LITIGATION")));

    public DirectoryResponse snapshot() {
        return new DirectoryResponse(PEOPLE, TEAMS);
    }
}
