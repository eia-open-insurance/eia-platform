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

## Edge authentication (opt-in)

JWT validation is disabled by default, so every exchange is permitted and the gateway keeps its public behaviour. Set `eia.auth.enabled=true` to keep `/actuator/health` and `/api/v1/directory` public and require a valid bearer JWT on every other exchange. The resource server verifies signatures against the configured JWK set, requires the configured issuer, and maps the `scope` claim to `SCOPE_`-prefixed authorities. CSRF is disabled because the gateway is a stateless proxy.

### Method-level scopes

When edge authentication is enabled, authenticated `/api/**` exchanges are also authorized by HTTP method:

| Methods | Required authority |
| --- | --- |
| `GET`, `HEAD`, `OPTIONS` | `SCOPE_eia.read` |
| `POST`, `PUT`, `PATCH`, `DELETE` | `SCOPE_eia.write` |

A valid token that lacks the required scope is rejected with `403 Forbidden`; a missing or invalid token stays `401 Unauthorized`. Health and the directory remain public.

### Tenant binding

When edge authentication is enabled, an authenticated exchange is bound to the tenant carried by the JWT claim configured through `eia.auth.tenant-claim` (default `tenant_id`). The gateway reconciles that claim with the `X-EIA-Tenant` request header:

| Request | Result |
| --- | --- |
| `X-EIA-Tenant` missing or blank | Injected from the token claim before proxying |
| `X-EIA-Tenant` equal to the claim | Forwarded unchanged |
| `X-EIA-Tenant` different from the claim | `403 Forbidden` with an `application/problem+json` body and `code` `TENANT_FORBIDDEN` |
| Token claim missing or blank | `403 Forbidden` with `code` `TENANT_FORBIDDEN` (fail closed) |

Public paths (`/actuator/health` and `/api/v1/directory`) are not tenant-scoped and bypass the check. The tenant filter is only installed when edge authentication is enabled, so a deployment with authentication disabled permits every exchange unchanged.

### Actor propagation

When a request is authenticated with a bearer JWT, the gateway sets `X-EIA-Actor` to the token `sub` before proxying the exchange. An `X-EIA-Actor` header supplied by the caller is never overwritten. The propagation filter is only installed when edge authentication is enabled.

| Property | Default | Purpose |
| --- | --- | --- |
| `eia.auth.enabled` | `false` | Turn on edge JWT validation |
| `eia.auth.jwks-uri` | `http://localhost:9000/oauth2/jwks` | JWK set used for signature verification |
| `eia.auth.issuer` | `http://localhost:9000` | Required `iss` claim |
| `eia.auth.tenant-claim` | `tenant_id` | JWT claim bound to `X-EIA-Tenant` |

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
