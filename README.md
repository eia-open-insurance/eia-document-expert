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
- Database: `eia_document` on `localhost:5432` (user `postgres`, password `postgres`). `schema.sql` creates the `document`, `document_link`, `document_content`, `document_content_version`, and outbox tables on start; the DDL is idempotent.
- Internal API: `GET|POST /internal/v1/documents`, `GET /internal/v1/documents/{id}`, `POST /internal/v1/documents/{id}/status`, `GET /internal/v1/documents/search`.
- Public read/link API:
  - `GET /api/v1/documents?linkedType=&linkedId=&q=` — list; the link pair filters documents associated with that subject, and `q` matches document metadata or any link's `linkedReference`.
  - `GET /api/v1/documents/search?q=&limit=` — EntityPicker-compatible `{query, limit, items}` envelope; `limit` is clamped to 1–25.
  - `GET /api/v1/documents/{documentId}/links` — list links.
  - `POST /api/v1/documents/{documentId}/links` — create a link, `201`; body `{linkedType, linkedId, linkedReference?}`.
  - `DELETE /api/v1/documents/{documentId}/links/{linkId}` — remove a link, `204`.
  - The same link routes are also exposed under `/internal/v1/documents`.
  - The tenant comes from `X-EIA-Tenant`; the internal `tenantId` query parameter is accepted as a fallback. A document or link outside the tenant is `404`.
  - `linkedType` is one of `DIRECT_CLAIM`, `ASSUMED_CLAIM`, `POLICY`, `CONTRACT`, `PARTY`, `PAYMENT`, `OTHER`. `linkedId` is required for every type; `linkedReference` is optional, at most 64 characters.
  - A duplicate `(tenant, document, linkedType, linkedId)` is `409 DUPLICATE_LINK`; no second row or event is written.
  - The link DTO is `{id, documentId, linkedType, linkedId, linkedReference, linkedLabel, createdAt}`; `linkedLabel` is the reference, or the first eight characters of `linkedId` when no reference was supplied.
  - Link writes append canonical `document.link.added` / `document.link.removed` outbox rows in the same transaction. The rows round-trip through `EventEnvelope`, so the dispatcher can publish them.
- Legal hold and disposition API:
  - `POST /api/v1/documents/{documentId}/hold` — place a legal hold; body `{reason}` (required, at most 500 characters). The document moves to `ON_HOLD`, the prior status is snapshotted in `statusBeforeHold`, and `heldAt`/`holdReason` are recorded. A second hold is `409 DOCUMENT_ALREADY_ON_HOLD`; a status outside `ACTIVE`, `ARCHIVED`, `CLASSIFIED`, `SUPERSEDED` is `409 HOLD_STATUS_NOT_ALLOWED`.
  - `POST /api/v1/documents/{documentId}/hold/release` — release the hold, restore `statusBeforeHold` (falling back to `ARCHIVED` when absent), and clear the hold fields. A document that is not `ON_HOLD` is `409 DOCUMENT_NOT_ON_HOLD`.
  - `POST /api/v1/documents/{documentId}/dispose` — tombstone the document as `DISPOSED` with `disposedAt`. Disposition requires a retention date on or before today (`409 RETENTION_NOT_EXPIRED`) and rejects `ON_HOLD` (`409 DOCUMENT_ON_LEGAL_HOLD`) or any status outside `ACTIVE`, `ARCHIVED`, `SUPERSEDED`. The optional body is `{reason}`; the event defaults to `RETENTION_EXPIRED`. When the document still has live content, the same transaction clears all current and version bytes and appends `document.content.deleted`; a document with no content or already-tombstoned content disposes with only `document.document.disposed`.
  - All three routes resolve the tenant from `X-EIA-Tenant` (with the internal `tenantId` query fallback) and hide documents outside that tenant as `404 NOT_FOUND`.
  - Each write appends its outbox fact in the same transaction: `document.legal-hold.placed`, `document.legal-hold.released`, or `document.document.disposed` (the canonical three-segment name required by the platform envelope), with `{documentId, documentNumber, reason, status}`. A dispose that tombstones live content appends `document.content.deleted` in the same transaction.
  - The document DTO adds `holdReason`, `heldAt`, `statusBeforeHold`, and `disposedAt` alongside the existing lifecycle fields.
- Automatic retention disposition (opt-in):
  - `eia.document.retention.enabled` (env `EIA_DOCUMENT_RETENTION_ENABLED`, default `false`) registers a Quartz job named `document.retention-disposition` in group `eia-document`. The scheduler starts by default; `EIA_QUARTZ_AUTO_STARTUP=false` turns the Quartz scheduler off for an environment that runs neither the outbox dispatcher nor retention.
  - `eia.document.retention.cron` (env `EIA_DOCUMENT_RETENTION_CRON`, default `0 0 2 * * ?`, Quartz six-field syntax in UTC) sets the trigger. `eia.document.retention.batch-size` (default `100`) caps one run; the next run picks up the remainder.
  - A document is due when its status is `ACTIVE`, `ARCHIVED`, or `SUPERSEDED`, its `retention_until` is on or before today (UTC), and it has neither a legal hold nor a previous disposition. `ON_HOLD` and `DISPOSED` documents are never selected.
  - Each due document goes through the normal disposition service with reason `RETENTION_EXPIRED` and a `SYSTEM` actor. `eia.document.retention.actor-id` (default all-zeros UUID) identifies that actor in the `document.document.disposed` and `document.content.deleted` facts.
  - Disposition runs in one transaction per document, so a failure is logged and counted without stopping the rest of the batch. The run logs `selected`, `disposed`, and `failed` counts.
  - The eligible documents are read through tenant-scoped queries; the job discovers tenants with due records and disposes every row under its own tenant.
- Document content API (append-only revisions; `document_content` is the current pointer):
  - `PUT /api/v1/documents/{documentId}/content` — append a revision and make it current. The body is raw bytes, `Content-Type` is the file MIME type, and `X-File-Name` is the URL-encoded filename. The maximum is 10 MiB; an empty body is `400 EMPTY_CONTENT` and an over-limit body is `413 FILE_TOO_LARGE`. Returns `200` with `{documentId, fileName, contentType, sizeBytes, sha256, uploadedAt, currentVersion, deletedAt, deleted}`.
  - `DELETE /api/v1/documents/{documentId}/content` — tombstone the content. Clears the bytes of the current pointer and every revision and records `deletedAt`, while keeping `fileName`, `contentType`, `sizeBytes`, `sha256`, `uploadedAt`, and `currentVersion`. Returns `204`; a document with no content is `404 CONTENT_NOT_FOUND`; a document under legal hold is `409 DOCUMENT_ON_LEGAL_HOLD` (SPEC DOC-06) and is left untouched; repeating the delete is `409 CONTENT_ALREADY_DELETED` and writes no second event.
  - `GET /api/v1/documents/{documentId}/content` — download the current bytes with the stored `Content-Type`, an attachment `Content-Disposition`, `X-Content-SHA256`, and `X-Content-Version`. Tombstoned content is `410 CONTENT_DELETED`.
  - `GET /api/v1/documents/{documentId}/content/metadata` — current metadata without bytes, including `currentVersion`, nullable `deletedAt`, and the derived `deleted` boolean; a document with no content is `404 CONTENT_NOT_FOUND`. Metadata stays readable after a tombstone.
  - `GET /api/v1/documents/{documentId}/content/versions` — list revision metadata newest first as `{documentId, version, fileName, contentType, sizeBytes, sha256, uploadedAt}`; a document with no content returns an empty list.
  - `GET /api/v1/documents/{documentId}/content/versions/{version}` — download one revision with the same headers as the current download; an unknown revision is `404 CONTENT_VERSION_NOT_FOUND` and a tombstoned revision is `410 CONTENT_DELETED`.
  - Content also resolves the tenant from `X-EIA-Tenant` (with the internal `tenantId` query fallback). A missing document or tenant is `404 NOT_FOUND`, so the tenant wall never reveals another tenant's document.
  - `sha256` is the lowercase SHA-256 of the exact stored bytes. Uploads append an immutable `document_content_version` row and a canonical `document.content.uploaded` outbox row in the same transaction with the document number, `version`, filename, MIME type, size, and digest. Deletes append `document.content.deleted` with `{documentId, documentNumber, fileName, sizeBytes, sha256, deletedAt}` in the same transaction as the tombstone. A dispose of live content performs the same tombstone and fact without the explicit delete's `404 CONTENT_NOT_FOUND` / `409 CONTENT_ALREADY_DELETED` responses.
  - A delete tombstones the content aggregate rather than adding a revision; a later upload clears the current tombstone and appends a new downloadable revision, while earlier revisions keep their original tombstone.
  - Existing content from before versioning is backfilled as version 1 when the schema runs.
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
