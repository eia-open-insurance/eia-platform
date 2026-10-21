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

## Local login

The authorization server is self-contained for local development:

| Setting | Value |
| --- | --- |
| Issuer | `http://localhost:9000` (override with `eia.auth.issuer` or `EIA_AUTH_ISSUER`) |
| Token endpoint | `POST http://localhost:9000/oauth2/token` |
| JWKS | `GET http://localhost:9000/oauth2/jwks` |
| Client | `eia-shell` / `shell-secret` (`client_secret_basic`) |
| User | `adjuster` / `password` (role `CLAIMS_ADJUSTER`) |

Request a token with the resource owner password grant:

```bash
curl -s -u eia-shell:shell-secret \
  -d 'grant_type=password&username=adjuster&password=password&scope=openid profile eia.read' \
  http://localhost:9000/oauth2/token
```

The `eia-shell` client also supports `authorization_code` and `refresh_token`
with redirect URI `http://localhost:3000/callback`.

Run the service with `./gradlew :auth:bootRun` and the tests with
`./gradlew :auth:test`.

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
