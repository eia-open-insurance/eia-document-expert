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
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_document_number UNIQUE (tenant_id, document_number)
);

-- H2-compatible copy of the document_link table.
CREATE TABLE IF NOT EXISTS document_link (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    linked_type VARCHAR(32) NOT NULL,
    linked_id UUID NOT NULL,
    linked_reference VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
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

-- H2-compatible copy of the document_content current pointer. H2 accepts BYTEA
-- in PostgreSQL mode; timestamps use the TIMESTAMP WITH TIME ZONE spelling.
CREATE TABLE IF NOT EXISTS document_content (
    document_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    current_version INTEGER NOT NULL DEFAULT 1,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_content_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE
);

-- Mirrors the production migration for databases created before versioning.
ALTER TABLE document_content
    ADD COLUMN IF NOT EXISTS current_version INTEGER NOT NULL DEFAULT 1;

-- H2-compatible copy of the immutable content revision table.
CREATE TABLE IF NOT EXISTS document_content_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    version INTEGER NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_document_content_version_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT uq_document_content_version
        UNIQUE (document_id, version)
);

CREATE INDEX IF NOT EXISTS idx_document_content_version_document
    ON document_content_version (document_id, version DESC);

-- Backfill content written before revisions existed as version 1.
INSERT INTO document_content_version (
    id, document_id, tenant_id, version, file_name, content_type, size_bytes,
    sha256, content, uploaded_at
)
SELECT RANDOM_UUID(), c.document_id, c.tenant_id, 1, c.file_name,
       c.content_type, c.size_bytes, c.sha256, c.content, c.uploaded_at
  FROM document_content c
 WHERE NOT EXISTS (
       SELECT 1
         FROM document_content_version v
        WHERE v.document_id = c.document_id
          AND v.version = 1
 )
ON CONFLICT DO NOTHING;

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
