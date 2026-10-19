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
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_document_number UNIQUE (tenant_id, document_number)
);

CREATE INDEX IF NOT EXISTS idx_document_tenant_created
    ON document (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_document_tenant_status
    ON document (tenant_id, status);
