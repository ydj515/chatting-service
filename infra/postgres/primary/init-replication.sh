#!/bin/sh
set -eu

replication_user="${POSTGRES_REPLICATION_USER:-chat_replicator}"
replication_password="${POSTGRES_REPLICATION_PASSWORD:-replicatorpass}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
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
SQL

{
  echo ""
  echo "# Allow local Docker replication connections."
  echo "host replication ${replication_user} all scram-sha-256"
} >> "$PGDATA/pg_hba.conf"
