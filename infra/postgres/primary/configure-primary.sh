#!/bin/sh
set -eu

db_host="${DB_HOST:-postgres}"
db_port="${DB_PORT:-5432}"
db_name="${DB_NAME:-chatdb}"
db_user="${DB_USERNAME:-chatuser}"
replication_user="${POSTGRES_REPLICATION_USER:-chat_replicator}"
replication_password="${POSTGRES_REPLICATION_PASSWORD:-replicatorpass}"

until pg_isready -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" >/dev/null 2>&1; do
  echo "Waiting for PostgreSQL primary configuration target..."
  sleep 2
done

psql -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
  --set=replication_user="$replication_user" \
  --set=replication_password="$replication_password" <<'SQL'
SELECT format(
    'CREATE ROLE %I WITH REPLICATION LOGIN PASSWORD %L',
    :'replication_user',
    :'replication_password'
)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'replication_user'
);
\gexec

SELECT pg_reload_conf();
SQL

if [ -f /sql/message-partitions.sql ]; then
  psql -v ON_ERROR_STOP=1 -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
    -f /sql/message-partitions.sql
fi

echo "PostgreSQL primary replication configuration is ready."
