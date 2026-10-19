# eia-platform/auth

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-platform/tree/main/auth
- Type: Spring Boot service
- Deployable: Yes
- Owner: Security
- Runtime: Java 22 and Spring Authorization Server
- Data store: eia_platform
- Depends on: eia-platform/libs

## Purpose

OIDC/OAuth2 authorization server, client registry, token issuance, and step-up authentication.

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

Integrates with the corporate identity provider.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
