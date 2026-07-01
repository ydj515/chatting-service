#!/bin/sh
set -eu

case "${ALERTMANAGER_PAGERDUTY_ENABLED:-true}" in
  true | TRUE | 1 | yes | YES | on | ON)
    ALERTMANAGER_PAGERDUTY_RECEIVER=pagerduty-critical
    ;;
  false | FALSE | 0 | no | NO | off | OFF)
    ALERTMANAGER_PAGERDUTY_RECEIVER=slack-warning
    ;;
  *)
    echo "ALERTMANAGER_PAGERDUTY_ENABLED must be true, false, 1, 0, yes, no, on, or off." >&2
    exit 64
    ;;
esac

export ALERTMANAGER_PAGERDUTY_RECEIVER

rendered_config=/tmp/alertmanager/alertmanager.yml
mkdir -p "$(dirname "$rendered_config")"

sed "s|\${ALERTMANAGER_PAGERDUTY_RECEIVER}|${ALERTMANAGER_PAGERDUTY_RECEIVER}|g" \
  /etc/alertmanager/alertmanager.yml > "$rendered_config"

exec /bin/alertmanager "$@"
