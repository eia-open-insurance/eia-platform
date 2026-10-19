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

## Transactional outbox

`com.eia.platform.libs.events` contains the canonical event envelope
(`EventEnvelope`, SPEC 7.5.4 / 12.4), deterministic UUIDv5 event ids, and the
canonical JSON mapper.

`com.eia.platform.libs.outbox` contains the reusable outbox foundation:

- `OutboxRepository` — Spring `JdbcTemplate` append, claim (`FOR UPDATE SKIP
  LOCKED` with a visibility lease), mark sent, mark failed with the SPEC 7.5.1
  backoff, and mark dead.
- `OutboxDispatcher` / `RestClientOutboxDispatcher` — authenticated HTTP only;
  no broker. Sets `X-EIA-Event-Id`, `X-EIA-Event-Type`,
  `X-EIA-Event-Version`, `X-EIA-Aggregate-Version`, `Idempotency-Key`, and the
  optional `X-EIA-Signature` HMAC.
- `OutboxDispatchService` — claims one batch, preserves per-partition order,
  and applies retry or dead-letter rules.
- `OutboxDispatchJob` — Quartz job that runs one dispatch batch.
- `OutboxProperties` — `eia.outbox.enabled`, `eia.outbox.endpoint`,
  `eia.outbox.batch-size`, plus retry and timeout tuning.

`OutboxAutoConfiguration` always provides the repository when JDBC is present;
dispatcher and dispatch-service beans are created only when
`eia.outbox.enabled=true`. Consumers own the Quartz schedule and the
`outbox_event` DDL in `src/main/resources/db/outbox_event.sql`.

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
