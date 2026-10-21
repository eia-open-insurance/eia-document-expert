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
- Database: `eia_document` on `localhost:5432` (user `postgres`, password `postgres`). `schema.sql` creates the `document`, `document_link`, `document_content`, and outbox tables on start; the DDL is idempotent.
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
- Document content API (one current content row per document):
  - `PUT /api/v1/documents/{documentId}/content` — replace the stored bytes. The body is raw bytes, `Content-Type` is the file MIME type, and `X-File-Name` is the URL-encoded filename. The maximum is 10 MiB; an empty body is `400 EMPTY_CONTENT` and an over-limit body is `413 FILE_TOO_LARGE`. Returns `200` with `{documentId, fileName, contentType, sizeBytes, sha256, uploadedAt}`.
  - `GET /api/v1/documents/{documentId}/content` — download the bytes with the stored `Content-Type`, an attachment `Content-Disposition`, and `X-Content-SHA256`.
  - `GET /api/v1/documents/{documentId}/content/metadata` — metadata without bytes; a document with no content is `404 CONTENT_NOT_FOUND`.
  - Content also resolves the tenant from `X-EIA-Tenant` (with the internal `tenantId` query fallback). A missing document or tenant is `404 NOT_FOUND`, so the tenant wall never reveals another tenant's document.
  - `sha256` is the lowercase SHA-256 of the exact stored bytes. Uploads append a canonical `document.content.uploaded` outbox row in the same transaction with the document number, filename, MIME type, size, and digest.
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
