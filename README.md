# eia-document-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-document-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Document
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_document (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Document persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Document ingestion, classification, retention, legal hold, print, e-signature, and e-discovery.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Local run

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat build
.\gradlew.bat bootRun
```

- Port: `8095`.
- Database: `eia_document` on `localhost:5432` (user `postgres`, password `postgres`). `schema.sql` creates the `document` table and sequence on start.
- Internal API: `GET|POST /internal/v1/documents`, `GET /internal/v1/documents/{id}`, `POST /internal/v1/documents/{id}/status`.
- The repository integration test runs against PostgreSQL when `EIA_TEST_DB=true` is set; otherwise it is skipped.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
