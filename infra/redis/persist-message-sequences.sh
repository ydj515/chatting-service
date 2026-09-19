#!/bin/sh
# Run against every Redis primary after stopping old sequence issuers.
# REDISCLI_AUTH is read by redis-cli; never pass credentials on the command line.
set -eu

prefix=${CHAT_REDIS_SEQUENCE_KEY_PREFIX:-chat:sequence}
if [ "$#" -eq 0 ]; then
  set -- "${REDIS_HOST:-127.0.0.1}:${REDIS_PORT:-6379}"
fi
scan_file=$(mktemp)
trap 'rm -f "$scan_file"' EXIT HUP INT TERM
count=0
for endpoint in "$@"; do
  host=${endpoint%:*}
  port=${endpoint##*:}
  redis-cli -e -c -h "$host" -p "$port" --scan --pattern "$prefix:*" > "$scan_file"
  while IFS= read -r key; do
    room_id=${key#"$prefix:"}
    case "$room_id" in ''|*[!0-9]*) continue ;; esac
    redis-cli -e -c -h "$host" -p "$port" PERSIST "$key" > /dev/null
    count=$((count + 1))
  done < "$scan_file"
done
printf 'Processed %s room sequence keys without changing their values.\n' "$count"
