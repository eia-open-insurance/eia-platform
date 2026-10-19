# eia-platform/libs

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-platform/tree/main/libs
- Type: Shared library
- Deployable: No
- Owner: Platform
- Runtime: Java 22
- Data store: Not applicable
- Depends on: eia-contracts

## Purpose

Shared Java libraries for error envelopes, observability, security context, and test fixtures.

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

No vertical business logic is permitted.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
