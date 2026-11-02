#!/usr/bin/env bash
# External watchdog for cron jobs: run "$@", pass its output through, and email when it exits non-zero
# (including a crash before the Java app could send its own alert, a missing JVM, a missing script).
# Usage: cron-wrapper.sh COMMAND [ARGS...]
# Env:   CANDLES_NOTIFY  notifier command (default: /usr/bin/python3 <repo>/bin/notify_email.py)
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
read -r -a NOTIFY <<< "${CANDLES_NOTIFY:-/usr/bin/python3 $REPO/bin/notify_email.py}"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 COMMAND [ARGS...]" >&2
  exit 64
fi

out="$(mktemp -t candles-cron.XXXXXX)"
body="$out.body"
trap 'rm -f "$out" "$body"' EXIT

"$@" 2>&1 | tee "$out"
code=${PIPESTATUS[0]}

if [ "$code" -ne 0 ]; then
  {
    echo "Command:   $*"
    echo "Exit code: $code"
    echo "Host:      $(hostname)"
    echo "Time:      $(date --iso-8601=seconds)"
    echo
    echo "Last 80 lines of output:"
    tail -n 80 "$out"
  } > "$body"
  if ! "${NOTIFY[@]}" --subject "MONITORING-GetCandlesBatch: run failed (exit $code)" --body-file "$body"; then
    echo "cron-wrapper: ALERT EMAIL FAILED for exit code $code" >&2
  fi
fi
exit "$code"
