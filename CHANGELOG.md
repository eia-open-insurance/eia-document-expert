# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Document association links: `document_link` table, tenant-scoped link API, document list/search filtering by linked subject and link reference, and transactional `document.link.added` / `document.link.removed` outbox facts.
- Document content: `document_content` table, raw-byte upload/download/metadata API with a 10 MiB limit and SHA-256 digest, tenant wall, and transactional `document.content.uploaded` outbox facts.
- Document content versioning: immutable `document_content_version` history, `current_version` pointer, version list/download routes, `version` in the upload event, legacy-content backfill as version 1, and `currentVersion` on the current metadata/download response.
- Legal hold and disposition: idempotent `hold_reason`, `held_at`, `status_before_hold`, and `disposed_at` columns; hold/release/dispose routes under `/api/v1/documents/{documentId}`; pre-hold status restore (ARCHIVED fallback); retention-expiry and legal-hold disposition guards; and transactional `document.legal-hold.placed`, `document.legal-hold.released`, and `document.document.disposed` outbox facts.
- Content tombstoning: idempotent nullable-bytes/`deleted_at` migration for `document_content` and `document_content_version`; tenant-scoped `DELETE /api/v1/documents/{documentId}/content`; legal-hold blocking (`409 DOCUMENT_ON_LEGAL_HOLD`); `410 CONTENT_DELETED` for tombstoned downloads; `deletedAt` and `deleted` on content metadata; repeat-delete `409 CONTENT_ALREADY_DELETED`; and transactional `document.content.deleted` outbox facts.
- Disposition content tombstone: a successful dispose of a document with live content clears the current pointer and every revision byte, records `deleted_at`, and appends the same `document.content.deleted` fact as an explicit delete in the disposition transaction; content-free and already-tombstoned documents dispose with only `document.document.disposed`.
- Automatic retention disposition: opt-in `eia.document.retention.*` configuration, a Quartz `document.retention-disposition` job (daily 02:00 UTC by default, batch size 100), tenant-scoped due-document selection for `ACTIVE`/`ARCHIVED`/`SUPERSEDED` rows past `retention_until`, legal-hold and disposed exclusion, per-document failure isolation with count logging, `RETENTION_EXPIRED` dispositions under a configurable system actor, and a retention scan index.
