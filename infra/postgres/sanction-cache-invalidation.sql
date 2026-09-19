-- Durable cache invalidation jobs. Apply before deploying the corresponding application version.
CREATE TABLE IF NOT EXISTS sanction_cache_invalidations (
    id VARCHAR(36) PRIMARY KEY,
    cache_key VARCHAR(100) NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    claim_token VARCHAR(36),
    claimed_until TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS ix_sanction_cache_invalidations_due
    ON sanction_cache_invalidations (available_at);
