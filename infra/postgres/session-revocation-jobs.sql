-- Apply before deploying the session revocation retry service.
CREATE TABLE IF NOT EXISTS session_revocation_jobs (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    claim_token VARCHAR(36),
    claimed_until TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS ix_session_revocation_jobs_due ON session_revocation_jobs (available_at);
