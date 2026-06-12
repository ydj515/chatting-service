#!/bin/sh
set -eu

db_host="${DB_HOST:-postgres}"
db_port="${DB_PORT:-5432}"
db_name="${DB_NAME:-chatdb}"
db_user="${DB_USERNAME:-chatuser}"
retention_days="${CHAT_MESSAGE_RETENTION_DAYS:-100}"
archive_dir="${CHAT_PARTITION_ARCHIVE_DIR:-/archive}"
drop_after_copy="${CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY:-false}"
run_once="${CHAT_PARTITION_ARCHIVE_RUN_ONCE:-false}"
interval_seconds="${CHAT_PARTITION_ARCHIVE_INTERVAL_SECONDS:-86400}"

mkdir -p "$archive_dir"

archive_once() {
  echo "Checking message partitions older than ${retention_days} days..."

  if ! psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -Atc \
    "SELECT to_regclass('public.chat_messages') IS NOT NULL;" | grep -q '^t$'; then
    echo "chat_messages parent table does not exist yet. Nothing to archive."
    return 0
  fi

  psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -At <<SQL > /tmp/chat-message-partitions.txt
WITH partitions AS (
    SELECT
        child.oid,
        child.relname AS partition_name,
        substring(child.relname from 'chat_messages_([0-9]{8})') AS partition_day
    FROM pg_inherits
    JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
    JOIN pg_class child ON pg_inherits.inhrelid = child.oid
    JOIN pg_namespace ns ON ns.oid = child.relnamespace
    WHERE parent.relname = 'chat_messages'
      AND ns.nspname = 'public'
)
SELECT partition_name
FROM partitions
WHERE partition_day IS NOT NULL
  AND to_date(partition_day, 'YYYYMMDD') < current_date - (${retention_days} * interval '1 day')
ORDER BY partition_name;
SQL

  if [ ! -s /tmp/chat-message-partitions.txt ]; then
    echo "No partitions are older than retention window."
    return 0
  fi

  while IFS= read -r partition_name; do
    [ -n "$partition_name" ] || continue
    archive_file="${archive_dir}/${partition_name}.csv"
    tmp_file="${archive_file}.tmp"

    echo "Archiving ${partition_name} to ${archive_file}..."
    rm -f "$tmp_file"
    psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
      -c "\\copy (SELECT * FROM public.${partition_name} ORDER BY created_at, room_id, room_seq) TO '${tmp_file}' WITH (FORMAT csv, HEADER true)"
    mv "$tmp_file" "$archive_file"

    if [ "$drop_after_copy" = "true" ]; then
      echo "Detaching and dropping ${partition_name} after successful archive..."
      psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
        -v ON_ERROR_STOP=1 \
        -c "ALTER TABLE public.chat_messages DETACH PARTITION public.${partition_name};" \
        -c "DROP TABLE public.${partition_name};"
    else
      echo "Keeping ${partition_name}; set CHAT_PARTITION_ARCHIVE_DROP_AFTER_COPY=true to detach/drop."
    fi
  done < /tmp/chat-message-partitions.txt
}

until pg_isready -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" >/dev/null 2>&1; do
  echo "Waiting for PostgreSQL primary before archive check..."
  sleep 2
done

while true; do
  archive_once

  if [ "$run_once" = "true" ]; then
    exit 0
  fi

  sleep "$interval_seconds"
done
