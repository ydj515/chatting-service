DO $$
BEGIN
    EXECUTE format(
        'GRANT ALL PRIVILEGES ON DATABASE %I TO %I',
        current_database(),
        current_user
    );
END;
$$;

-- UUID 지원 확장 모듈을 활성화한다.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 성능 모니터링용 확장 모듈을 활성화한다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 연결 풀과 일반적인 OLTP 워크로드를 기준으로 한 PostgreSQL 튜닝값이다.
ALTER SYSTEM SET max_connections = '200';
ALTER SYSTEM SET shared_buffers = '256MB';
ALTER SYSTEM SET effective_cache_size = '1GB';
ALTER SYSTEM SET maintenance_work_mem = '64MB';
ALTER SYSTEM SET checkpoint_completion_target = '0.9';
ALTER SYSTEM SET wal_buffers = '16MB';
ALTER SYSTEM SET default_statistics_target = '100';

CREATE OR REPLACE FUNCTION update_modified_time()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';
