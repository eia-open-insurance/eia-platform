# Changelog

## [Unreleased]

### Added

- `event-ingest` service (port 8102): canonical event-envelope validation, idempotent
  `inbox_event` persistence in `eia_platform`, optional HMAC verification, and
  `/api/v1/events/{recent,stats}` read endpoints.
- Gateway route `/api/v1/events/**` to event ingest.

## [0.1.0] - 2026-10-19

### Added

- Consolidated repository baseline.
