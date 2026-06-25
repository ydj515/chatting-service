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
hash_partitions="${CHAT_MESSAGE_HASH_PARTITIONS:-16}"
object_storage_enabled="${CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED:-false}"
object_storage_endpoint="${CHAT_OBJECT_STORAGE_ENDPOINT:-}"
object_storage_bucket="${CHAT_OBJECT_STORAGE_BUCKET:-}"
object_storage_prefix="${CHAT_PARTITION_ARCHIVE_OBJECT_PREFIX:-postgres/archive/chat_messages}"

is_true() {
  [ "$1" = "true" ] || [ "$1" = "1" ] || [ "$1" = "yes" ]
}

require_unsigned_integer() {
  value="$1"
  name="$2"
  case "$value" in
    ''|*[!0-9]*)
      echo "${name} must be an unsigned integer." >&2
      exit 1
      ;;
  esac
}

require_positive_integer() {
  value="$1"
  name="$2"
  require_unsigned_integer "$value" "$name"
  if [ "$value" -lt 1 ]; then
    echo "${name} must be greater than 0." >&2
    exit 1
  fi
}

require_unsigned_integer "$retention_days" "CHAT_MESSAGE_RETENTION_DAYS"
require_positive_integer "$hash_partitions" "CHAT_MESSAGE_HASH_PARTITIONS"

mkdir -p "$archive_dir"

object_key_for() {
  basename="$1"
  prefix="$(printf "%s" "$object_storage_prefix" | sed 's#^/*##; s#/*$##')"
  if [ -z "$prefix" ]; then
    printf "%s" "$basename"
  else
    printf "%s/%s" "$prefix" "$basename"
  fi
}

require_object_storage_for_drop() {
  if is_true "$drop_after_copy" && ! is_true "$object_storage_enabled"; then
    echo "Object Storage upload is required before detach/drop. Enable CHAT_PARTITION_ARCHIVE_OBJECT_STORAGE_ENABLED=true." >&2
    exit 1
  fi
}

upload_archive_to_object_storage() {
  if ! is_true "$object_storage_enabled"; then
    return 0
  fi
  if [ -z "$object_storage_endpoint" ] || [ -z "$object_storage_bucket" ]; then
    echo "Object Storage endpoint and bucket are required when archive upload is enabled." >&2
    exit 1
  fi
  echo "Uploading ${archive_file} to ${archive_object_uri}..."
  aws --endpoint-url "$object_storage_endpoint" s3 cp "$archive_file" "s3://${object_storage_bucket}/${archive_object_key}"
  echo "Uploading ${metadata_file} to ${metadata_object_uri}..."
  aws --endpoint-url "$object_storage_endpoint" s3 cp "$metadata_file" "s3://${object_storage_bucket}/${metadata_object_key}"
}

require_object_storage_for_drop

precreate_partitions() {
  echo "Ensuring chat message partitions for today and tomorrow..."
  if ! psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -Atc \
    "SELECT to_regprocedure('public.create_chat_messages_daily_partition(date, integer)') IS NOT NULL;" | grep -q '^t$'; then
    echo "partition creation function does not exist yet. Skipping precreate."
    return 0
  fi

  psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -v ON_ERROR_STOP=1 -At <<SQL
SELECT create_chat_messages_daily_partition(current_date, ${hash_partitions});
SELECT create_chat_messages_daily_partition(current_date + 1, ${hash_partitions});
SQL
}

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
    metadata_file="${archive_file}.metadata.json"
    tmp_file="${archive_file}.tmp"
    archive_object_key="$(object_key_for "$(basename "$archive_file")")"
    metadata_object_key="$(object_key_for "$(basename "$metadata_file")")"
    archive_object_uri=""
    metadata_object_uri=""
    if is_true "$object_storage_enabled"; then
      archive_object_uri="s3://${object_storage_bucket}/${archive_object_key}"
      metadata_object_uri="s3://${object_storage_bucket}/${metadata_object_key}"
    fi

    echo "Archiving ${partition_name} to ${archive_file}..."
    rm -f "$tmp_file"
    row_count="$(psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" -Atc "SELECT count(*) FROM public.${partition_name};")"
    psql -h "$db_host" -p "$db_port" -U "$db_user" -d "$db_name" \
      -c "\\copy (SELECT * FROM public.${partition_name} ORDER BY created_at, room_id, room_seq) TO '${tmp_file}' WITH (FORMAT csv, HEADER true)"
    mv "$tmp_file" "$archive_file"
    checksum="$(sha256sum "$archive_file" | awk '{print $1}')"
    bytes="$(wc -c < "$archive_file" | tr -d ' ')"
    archived_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    uploaded_at=""
    if is_true "$object_storage_enabled"; then
      uploaded_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    fi
    cat > "$metadata_file" <<JSON
{
  "partitionName": "${partition_name}",
  "archiveFile": "$(basename "$archive_file")",
  "objectUri": "${archive_object_uri}",
  "metadataObjectUri": "${metadata_object_uri}",
  "sha256": "${checksum}",
  "bytes": ${bytes},
  "rowCount": ${row_count},
  "archivedAt": "${archived_at}",
  "uploadedAt": "${uploaded_at}"
}
JSON
    echo "Wrote archive metadata to ${metadata_file}."
    upload_archive_to_object_storage

    if is_true "$drop_after_copy"; then
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
  precreate_partitions
  archive_once

  if [ "$run_once" = "true" ]; then
    exit 0
  fi

  sleep "$interval_seconds"
done
