# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Document association links: `document_link` table, tenant-scoped link API, document list/search filtering by linked subject and link reference, and transactional `document.link.added` / `document.link.removed` outbox facts.
- Document content: `document_content` table, raw-byte upload/download/metadata API with a 10 MiB limit and SHA-256 digest, tenant wall, and transactional `document.content.uploaded` outbox facts.
- Document content versioning: immutable `document_content_version` history, `current_version` pointer, version list/download routes, `version` in the upload event, legacy-content backfill as version 1, and `currentVersion` on the current metadata/download response.
