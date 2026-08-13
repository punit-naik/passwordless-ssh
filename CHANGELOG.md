# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project uses semantic versioning.

## [1.0.1] - 2026-08-13

### Changed

- Promoted all helper functions in `passwordless-ssh.core` from private to public so downstream consumers can reuse the lower-level SSH bootstrap building blocks directly.
- Updated unit tests to call the promoted functions through the public namespace API instead of private var access.
- Updated README dependency coordinates and API notes to reflect the expanded public surface.

## [1.0.0] - 2026-08-13

Initial standalone release of `org.clojars.punit-naik/passwordless-ssh`.

### Added

- Standalone Leiningen project for `org.clojars.punit-naik/passwordless-ssh`.
- Public SSH bootstrap API in `passwordless-ssh.core`.
- Password-based and private-key-based SSH authentication support.
- Passwordless SSH mesh setup across compatible machine groups.
- Optional machine `:id` support as a stable ordering hint when present.
- Optional machine `:hostname` support as an additional `known_hosts` identifier.
- Automatic SSH keypair creation on target machines when missing.
- `known_hosts` population using machine IPs and optional hostnames.
- `authorized_keys` propagation across every machine in a compatible group.
- Unit test coverage for connection handling, grouping behavior, localhost handling, and SSH bootstrap orchestration.
- Docker-backed integration test coverage for a real three-node passwordless SSH cluster simulation.
- `test-cluster-simulation` Leiningen alias for Docker-backed integration testing.
- `PASSWORDLESS_SSH_DOCKER_KEEP_CLUSTER=true` support for leaving the simulated cluster running after tests for manual inspection.
- Test resources under `test-resources/` with Docker Compose and container image definitions.
- `:docker-integration` namespace selector for Docker-backed integration coverage.
- README and project metadata aligned with the released API, test commands, and EPL 2.0 licensing.
