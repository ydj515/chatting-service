#!/bin/sh
set -eu

primary_host="${POSTGRES_PRIMARY_HOST:-postgres}"
primary_port="${POSTGRES_PRIMARY_PORT:-5432}"
replication_user="${POSTGRES_REPLICATION_USER:-chat_replicator}"
replication_password="${POSTGRES_REPLICATION_PASSWORD:-replicatorpass}"
timezone="${DB_TIMEZONE:-Asia/Seoul}"

mkdir -p "$PGDATA"
chown -R postgres:postgres "$PGDATA"
chmod 700 "$PGDATA"

if [ ! -f "$PGDATA/standby.signal" ]; then
  echo "Waiting for PostgreSQL primary at ${primary_host}:${primary_port}..."
  until pg_isready -h "$primary_host" -p "$primary_port" >/dev/null 2>&1; do
    sleep 2
  done

  echo "Bootstrapping replica with pg_basebackup..."
  rm -rf "$PGDATA"/*
  export PGPASSWORD="$replication_password"
  pg_basebackup \
    -h "$primary_host" \
    -p "$primary_port" \
    -D "$PGDATA" \
    -U "$replication_user" \
    -Fp \
    -Xs \
    -P \
    -R
  unset PGPASSWORD
  chown -R postgres:postgres "$PGDATA"
  chmod 700 "$PGDATA"
fi

if command -v gosu >/dev/null 2>&1; then
  exec gosu postgres postgres -c "hot_standby=on" -c "timezone=${timezone}"
fi

if command -v su-exec >/dev/null 2>&1; then
  exec su-exec postgres postgres -c "hot_standby=on" -c "timezone=${timezone}"
fi

exec su postgres -c "postgres -c hot_standby=on -c timezone=${timezone}"
