# eia-platform/gateway

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-platform/tree/main/gateway
- Type: Spring Boot service
- Deployable: Yes
- Owner: Platform
- Runtime: Java 22 and Spring Cloud Gateway
- Data store: eia_platform
- Depends on: eia-platform/auth, eia-contracts, eia-platform/libs

## Purpose

Edge routing, session, rate limiting, CSRF, request correlation, and API aggregation.

## Public endpoints

- `GET /api/v1/directory` — read-only people and team directory used by the shell for assignee and queue labels. It is public (no `X-EIA-Tenant` header required), served locally by the gateway, and deliberately outside the proxied `/api/v1/*` route families. Responses keep a stable people and team order.

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

Single public API entry point.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
