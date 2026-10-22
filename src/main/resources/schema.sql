CREATE SEQUENCE IF NOT EXISTS document_number_seq START 1;

CREATE TABLE IF NOT EXISTS document (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_number VARCHAR(32) NOT NULL,
    title VARCHAR(300) NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retention_until DATE,
    hold_reason TEXT,
    held_at TIMESTAMPTZ,
    status_before_hold VARCHAR(16),
    disposed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_document_number UNIQUE (tenant_id, document_number)
);

-- Legal hold and disposition fields. A hold snapshots the status it suspended
-- so a release can restore it; disposed_at is the tombstone timestamp. The
-- ALTERs are idempotent so databases created before these columns existed pick
-- them up on the next start.
ALTER TABLE document ADD COLUMN IF NOT EXISTS hold_reason TEXT;
ALTER TABLE document ADD COLUMN IF NOT EXISTS held_at TIMESTAMPTZ;
ALTER TABLE document ADD COLUMN IF NOT EXISTS status_before_hold VARCHAR(16);
ALTER TABLE document ADD COLUMN IF NOT EXISTS disposed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_document_tenant_created
    ON document (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_document_tenant_status
    ON document (tenant_id, status);

-- A document may be associated with records owned by other verticals. The
-- target id is authoritative in its owning vertical; linked_reference is only
-- a display snapshot. Idempotent so schema.sql can run on every start.
CREATE TABLE IF NOT EXISTS document_link (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    linked_type VARCHAR(32) NOT NULL,
    linked_id UUID NOT NULL,
    linked_reference VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_document_link_document
        FOREIGN KEY (document_id) REFERENCES document (id),
    CONSTRAINT uq_document_link_subject
        UNIQUE (tenant_id, document_id, linked_type, linked_id),
    CONSTRAINT ck_document_link_linked_type
        CHECK (linked_type IN ('DIRECT_CLAIM', 'ASSUMED_CLAIM', 'POLICY', 'CONTRACT',
                               'PARTY', 'PAYMENT', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_document_link_subject
    ON document_link (tenant_id, linked_type, linked_id);

-- Current content pointer for a document. At most one row exists per document;
-- an upload replaces the metadata and bytes in place and advances
-- current_version to the revision it just wrote. A content tombstone keeps the
-- metadata and nulls content while recording deleted_at; a later upload clears
-- the tombstone and appends a new revision. The immutable revision rows live in
-- document_content_version. The foreign key cascades so deleting a document
-- row removes the pointer and every stored revision; disposition tombstones the
-- bytes in place without deleting the document row.
CREATE TABLE IF NOT EXISTS document_content (
    document_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content BYTEA,
    current_version INTEGER NOT NULL DEFAULT 1,
    uploaded_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_document_content_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
);

-- Databases created before content versioning have content_version instead of
-- current_version. Add the pointer column without rewriting the table; the
-- legacy column is left in place and is no longer read.
ALTER TABLE document_content
    ADD COLUMN IF NOT EXISTS current_version INTEGER NOT NULL DEFAULT 1;

-- Content tombstones: deleted_at records when the bytes were cleared. The
-- content column becomes nullable so a tombstone can keep the file metadata
-- (name, type, size, digest, version) without retaining the bytes. Both ALTERs
-- are idempotent so databases created before tombstoning pick them up on the
-- next start.
ALTER TABLE document_content
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE document_content
    ALTER COLUMN content DROP NOT NULL;

-- Immutable content revisions. (document_id, version) identifies a revision
-- and the index serves the newest-first version list.
CREATE TABLE IF NOT EXISTS document_content_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    version INTEGER NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content BYTEA,
    uploaded_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_document_content_version_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT uq_document_content_version
        UNIQUE (document_id, version)
);

-- The revision table mirrors the tombstone columns. A delete clears the bytes
-- and sets deleted_at on every revision while retaining its metadata, so the
-- history remains auditable after the content is gone.
ALTER TABLE document_content_version
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE document_content_version
    ALTER COLUMN content DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_document_content_version_document
    ON document_content_version (document_id, version DESC);

-- Content stored before revisions existed becomes version 1. The NOT EXISTS
-- guard and ON CONFLICT keep the backfill safe on every application start and
-- when more than one instance starts at once.
INSERT INTO document_content_version (
    id, document_id, tenant_id, version, file_name, content_type, size_bytes,
    sha256, content, uploaded_at, deleted_at
)
SELECT gen_random_uuid(), c.document_id, c.tenant_id, 1, c.file_name,
       c.content_type, c.size_bytes, c.sha256, c.content, c.uploaded_at,
       c.deleted_at
  FROM document_content c
 WHERE NOT EXISTS (
       SELECT 1
         FROM document_content_version v
        WHERE v.document_id = c.document_id
          AND v.version = 1
 )
ON CONFLICT DO NOTHING;

-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
