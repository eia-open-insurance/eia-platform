# eia-platform/config

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-platform/tree/main/config
- Type: Spring Boot service
- Deployable: Yes
- Owner: Platform
- Runtime: Java 22 and Spring Cloud Config
- Data store: Git or native file system
- Depends on: eia-platform/libs

## Purpose

Central configuration and feature-flag service.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Configuration is versioned and audited.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
