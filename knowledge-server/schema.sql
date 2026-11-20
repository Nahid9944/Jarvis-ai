-- NAHID AI Independent Firebase-Free Knowledge Engine Schema (PostgreSQL 15+)
-- Zero Firebase dependency. Supports canonical URLs, versioning, chunking, claim verification,
-- async research jobs, ETag/Last-Modified fetch cache, token auth, audit logs, and user feedback.

CREATE TABLE IF NOT EXISTS sources (
    source_id VARCHAR(128) PRIMARY KEY,
    display_name TEXT NOT NULL,
    domain VARCHAR(255) NOT NULL UNIQUE,
    category VARCHAR(64) NOT NULL,
    authority_weight REAL NOT NULL DEFAULT 0.50,
    min_interval_ms INTEGER NOT NULL DEFAULT 1500,
    respect_robots BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_crawled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS documents (
    document_id VARCHAR(64) PRIMARY KEY,
    canonical_url TEXT NOT NULL UNIQUE,
    domain VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    topic_category VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
    language VARCHAR(16) NOT NULL DEFAULT 'bn',
    content_hash VARCHAR(64) NOT NULL,
    verification_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    summary_bn TEXT NOT NULL DEFAULT '',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_documents_domain ON documents(domain);
CREATE INDEX IF NOT EXISTS idx_documents_expires_at ON documents(expires_at);
CREATE INDEX IF NOT EXISTS idx_documents_content_hash ON documents(content_hash);

CREATE TABLE IF NOT EXISTS document_versions (
    version_id BIGSERIAL PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    raw_excerpt TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (document_id, version_number)
);

CREATE TABLE IF NOT EXISTS chunks (
    chunk_id BIGSERIAL PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    token_count INTEGER NOT NULL DEFAULT 0,
    tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', chunk_text)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON chunks USING GIN(tsv);

CREATE TABLE IF NOT EXISTS facts_claims (
    claim_id VARCHAR(64) PRIMARY KEY,
    normalized_topic VARCHAR(255) NOT NULL,
    claim_text TEXT NOT NULL,
    verification_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
    independent_domain_count INTEGER NOT NULL DEFAULT 0,
    confidence_score REAL NOT NULL DEFAULT 0.50,
    conflicting_notes TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_facts_claims_status ON facts_claims(verification_status);

CREATE TABLE IF NOT EXISTS evidence_links (
    evidence_id BIGSERIAL PRIMARY KEY,
    claim_id VARCHAR(64) NOT NULL REFERENCES facts_claims(claim_id) ON DELETE CASCADE,
    document_id VARCHAR(64) NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    domain VARCHAR(255) NOT NULL,
    support_stance VARCHAR(24) NOT NULL DEFAULT 'SUPPORTS', -- SUPPORTS, CONTRADICTS, NEUTRAL
    snippet TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS research_jobs (
    job_id VARCHAR(64) PRIMARY KEY,
    query TEXT NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'DEEP_RESEARCH',
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED', -- QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
    target_urls_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    result_summary_bn TEXT NOT NULL DEFAULT '',
    error_message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fetch_cache (
    canonical_url TEXT PRIMARY KEY,
    etag VARCHAR(255) NOT NULL DEFAULT '',
    last_modified VARCHAR(255) NOT NULL DEFAULT '',
    http_status INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL DEFAULT '',
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS api_clients (
    client_id VARCHAR(64) PRIMARY KEY,
    client_name VARCHAR(128) NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    rate_limit_per_min INTEGER NOT NULL DEFAULT 60,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS audit_logs (
    audit_id BIGSERIAL PRIMARY KEY,
    client_id VARCHAR(64) NOT NULL DEFAULT '',
    action VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL DEFAULT '',
    status_code INTEGER NOT NULL,
    details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_feedback (
    feedback_id BIGSERIAL PRIMARY KEY,
    query TEXT NOT NULL,
    claim_id VARCHAR(64) NOT NULL DEFAULT '',
    rating VARCHAR(24) NOT NULL, -- HELPFUL, INACCURATE, OUTDATED
    correction_note TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
