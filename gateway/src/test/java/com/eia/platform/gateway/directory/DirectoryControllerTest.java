package com.eia.platform.gateway.directory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the public directory through the running gateway, not through the
 * controller in isolation, so the tests also prove that the local endpoint wins
 * over the proxied {@code /api/v1/*} route families.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class DirectoryControllerTest {

    private static final String DIRECTORY_PATH = "/api/v1/directory";

    @Autowired
    private WebTestClient client;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void servesDirectoryWithExpectedStatusShapeAndCounts() {
        client.get().uri(DIRECTORY_PATH).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.people.length()").isEqualTo(4)
                .jsonPath("$.teams.length()").isEqualTo(6)
                .jsonPath("$.people[0].id").isEqualTo("a-adjuster")
                .jsonPath("$.people[0].displayName").isEqualTo("A. Adjuster")
                .jsonPath("$.people[0].email").isEqualTo("a.adjuster@eia.example")
                .jsonPath("$.people[0].role").isEqualTo("CLAIMS_ADJUSTER")
                .jsonPath("$.people[0].teams.length()").isEqualTo(2)
                .jsonPath("$.people[0].teams[0]").isEqualTo("DIRECT_CLAIMS")
                .jsonPath("$.people[0].teams[1]").isEqualTo("MAJOR_LOSS")
                .jsonPath("$.teams[0].code").isEqualTo("DIRECT_CLAIMS")
                .jsonPath("$.teams[0].label").isEqualTo("Direct claims");
    }

    @Test
    void returnsPeopleInStableDirectoryOrder() {
        DirectoryResponse body = fetch();

        assertThat(body.people()).containsExactly(
                new DirectoryPerson("a-adjuster", "A. Adjuster", "a.adjuster@eia.example",
                        "CLAIMS_ADJUSTER", List.of("DIRECT_CLAIMS", "MAJOR_LOSS")),
                new DirectoryPerson("b-examiner", "B. Examiner", "b.examiner@eia.example",
                        "CLAIMS_EXAMINER", List.of("ASSUMED_CLAIMS", "RECOVERY")),
                new DirectoryPerson("c-manager", "C. Manager", "c.manager@eia.example",
                        "CLAIMS_MANAGER", List.of("SUPERVISORY", "LITIGATION")),
                new DirectoryPerson("d-specialist", "D. Specialist", "d.specialist@eia.example",
                        "MAJOR_LOSS_SPECIALIST", List.of("MAJOR_LOSS", "LITIGATION")));
    }

    @Test
    void returnsTeamsInStableDirectoryOrder() {
        DirectoryResponse body = fetch();

        assertThat(body.teams()).containsExactly(
                new DirectoryTeam("DIRECT_CLAIMS", "Direct claims"),
                new DirectoryTeam("ASSUMED_CLAIMS", "Assumed claims"),
                new DirectoryTeam("MAJOR_LOSS", "Major loss"),
                new DirectoryTeam("LITIGATION", "Litigation"),
                new DirectoryTeam("RECOVERY", "Recovery"),
                new DirectoryTeam("SUPERVISORY", "Supervisory"));
    }

    @Test
    void isPublicRequiresNoTenantHeaderAndIsDeterministic() {
        // No X-EIA-Tenant, Authorization, or correlation header is sent.
        DirectoryResponse first = fetch();
        DirectoryResponse second = fetch();

        assertThat(first.people()).isNotEmpty();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void everyPersonBelongsToDeclaredTeamsWithUniqueIdentifiers() {
        DirectoryResponse body = fetch();
        Set<String> teamCodes = body.teams().stream()
                .map(DirectoryTeam::code)
                .collect(Collectors.toSet());

        assertThat(body.people()).extracting(DirectoryPerson::id).doesNotHaveDuplicates();
        assertThat(body.people()).extracting(DirectoryPerson::email).doesNotHaveDuplicates();
        assertThat(body.people()).allSatisfy(person -> {
            assertThat(person.id()).isNotBlank();
            assertThat(person.displayName()).isNotBlank();
            assertThat(person.email()).contains("@");
            assertThat(person.role()).isNotBlank();
            assertThat(person.teams()).isNotEmpty().allMatch(teamCodes::contains);
        });
    }

    @Test
    void directoryPathIsServedLocallyAndIsNotAGatewayRoute() {
        // A 200 with the local directory body would be impossible if a proxied
        // route claimed the path: the target services are not running in tests.
        client.get().uri(DIRECTORY_PATH).exchange().expectStatus().isOk();

        AntPathMatcher matcher = new AntPathMatcher();
        List<String> pathPatterns = gatewayProperties.getRoutes().stream()
                .flatMap(route -> route.getPredicates().stream())
                .filter(predicate -> "Path".equals(predicate.getName()))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .toList();

        assertThat(pathPatterns).isNotEmpty();
        assertThat(pathPatterns).noneMatch(pattern -> matcher.match(pattern, DIRECTORY_PATH));
    }

    private DirectoryResponse fetch() {
        DirectoryResponse body = client.get().uri(DIRECTORY_PATH).exchange()
                .expectStatus().isOk()
                .expectBody(DirectoryResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }
}
