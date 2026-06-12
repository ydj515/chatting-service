-- Prototype partitioned message store for the PostgreSQL-first scaling path.
-- The current application still writes to the JPA-managed messages table; this
-- table is prepared for the Message Writer Worker phase.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS chat_messages (
    message_id uuid NOT NULL,
    client_message_id text,
    room_id bigint NOT NULL,
    room_seq bigint NOT NULL,
    write_shard smallint NOT NULL DEFAULT 0,
    sender_id bigint NOT NULL,
    message_type varchar(20) NOT NULL,
    content text,
    content_tsv tsvector,
    is_deleted boolean NOT NULL DEFAULT false,
    moderation_flags text[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    PRIMARY KEY (created_at, room_id, write_shard, message_id)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS chat_messages_default
PARTITION OF chat_messages DEFAULT;

CREATE TABLE IF NOT EXISTS room_storage_configs (
    room_id bigint PRIMARY KEY,
    current_shard_count integer NOT NULL DEFAULT 1,
    hot_room_policy varchar(50) NOT NULL DEFAULT 'NORMAL',
    bucket_granularity varchar(20) NOT NULL DEFAULT 'DAY',
    retention_days integer NOT NULL DEFAULT 100,
    archive_enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_chat_messages_default_room_time
ON chat_messages_default (room_id, created_at DESC, room_seq DESC);

CREATE INDEX IF NOT EXISTS ix_chat_messages_default_sender_time
ON chat_messages_default (sender_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_chat_messages_default_content_tsv
ON chat_messages_default USING gin (content_tsv);

CREATE INDEX IF NOT EXISTS ix_chat_messages_default_content_trgm
ON chat_messages_default USING gin (content gin_trgm_ops);

CREATE OR REPLACE FUNCTION set_chat_message_tsv()
RETURNS trigger AS $$
BEGIN
    NEW.content_tsv = to_tsvector('simple', coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_chat_messages_default_tsv ON chat_messages_default;
CREATE TRIGGER trg_chat_messages_default_tsv
BEFORE INSERT OR UPDATE OF content
ON chat_messages_default
FOR EACH ROW
EXECUTE FUNCTION set_chat_message_tsv();

CREATE OR REPLACE FUNCTION create_chat_messages_daily_partition(
    partition_day date,
    hash_partitions integer DEFAULT 16
)
RETURNS void AS $$
DECLARE
    parent_name text := format('chat_messages_%s', to_char(partition_day, 'YYYYMMDD'));
    child_name text;
    start_at timestamptz := partition_day::timestamptz;
    end_at timestamptz := (partition_day + 1)::timestamptz;
    i integer;
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF chat_messages FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (room_id, write_shard)',
        parent_name,
        start_at,
        end_at
    );

    FOR i IN 0..hash_partitions - 1 LOOP
        child_name := format('%s_h%s', parent_name, lpad(i::text, 2, '0'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
            child_name,
            parent_name,
            hash_partitions,
            i
        );

        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS %I ON %I (room_id, room_seq)',
            format('ux_%s_room_seq', child_name),
            child_name
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (room_id, created_at DESC, room_seq DESC)',
            format('ix_%s_room_time', child_name),
            child_name
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I (sender_id, created_at DESC)',
            format('ix_%s_sender_time', child_name),
            child_name
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I USING gin (content_tsv)',
            format('ix_%s_content_tsv', child_name),
            child_name
        );

        EXECUTE format(
            'CREATE INDEX IF NOT EXISTS %I ON %I USING gin (content gin_trgm_ops)',
            format('ix_%s_content_trgm', child_name),
            child_name
        );

        EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I', format('trg_%s_tsv', child_name), child_name);
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE OF content ON %I FOR EACH ROW EXECUTE FUNCTION set_chat_message_tsv()',
            format('trg_%s_tsv', child_name),
            child_name
        );
    END LOOP;
END;
$$ LANGUAGE plpgsql;

SELECT create_chat_messages_daily_partition(current_date, 16);
SELECT create_chat_messages_daily_partition(current_date + 1, 16);
