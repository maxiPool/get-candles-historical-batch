#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2034  # checks are eval-ed later on purpose
# Tests for bin/cron-wrapper.sh with a stub notifier.  Run: bash tests/test_cron_wrapper.sh
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="$HERE/../bin/cron-wrapper.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
fails=0

cat > "$T/notify" <<'EOF'
#!/usr/bin/env bash
# records the subject and the body
while [ $# -gt 0 ]; do
  case "$1" in
    --subject) echo "SUBJECT: $2" >> "$NOTIFY_LOG"; shift 2 ;;
    --body-file) cat "$2" >> "$NOTIFY_LOG"; shift 2 ;;
    *) shift ;;
  esac
done
exit "${NOTIFY_EXIT:-0}"
EOF
chmod +x "$T/notify"
export CANDLES_NOTIFY="$T/notify"
export NOTIFY_LOG="$T/notify.log"

check() { # name, condition
  if eval "$2"; then echo "ok   - $1"; else echo "FAIL - $1"; fails=$((fails + 1)); fi
}

# 1) success: output passes through, no email, exit 0
rm -f "$NOTIFY_LOG"
out="$("$WRAPPER" bash -c 'echo hello; exit 0')"; code=$?
check "success exits 0" '[ $code -eq 0 ]'
check "success passes output through" '[ "$out" = "hello" ]'
check "success sends no email" '[ ! -e "$NOTIFY_LOG" ]'

# 2) non-zero exit: same exit code, email with subject, exit code and output tail
rm -f "$NOTIFY_LOG"
"$WRAPPER" bash -c 'echo "Data folder is missing"; echo err >&2; exit 1' > "$T/o2" 2>&1; code=$?
check "failure keeps exit code 1" '[ $code -eq 1 ]'
check "failure emails a subject with the exit code" 'grep -q "SUBJECT: MONITORING-GetCandlesBatch: run failed (exit 1)" "$NOTIFY_LOG"'
check "failure email includes output and stderr" 'grep -q "Data folder is missing" "$NOTIFY_LOG" && grep -q "^err$" "$NOTIFY_LOG"'
check "failure output still reaches the log" 'grep -q "Data folder is missing" "$T/o2"'

# 3) crash with an unusual code (JVM killed, 137)
rm -f "$NOTIFY_LOG"
"$WRAPPER" bash -c 'exit 137' > /dev/null 2>&1; code=$?
check "crash code propagates" '[ $code -eq 137 ]'
check "crash emails" 'grep -q "exit 137" "$NOTIFY_LOG"'

# 4) missing command (deleted script, broken PATH)
rm -f "$NOTIFY_LOG"
"$WRAPPER" "$T/does-not-exist.sh" > /dev/null 2>&1; code=$?
check "missing command exits 127" '[ $code -eq 127 ]'
check "missing command emails" 'grep -q "exit 127" "$NOTIFY_LOG"'

# 5) the notifier itself fails: exit code unchanged, loud line in the log
rm -f "$NOTIFY_LOG"
NOTIFY_EXIT=1 "$WRAPPER" bash -c 'exit 1' > "$T/o5" 2>&1; code=$?
check "notifier failure keeps the job's exit code" '[ $code -eq 1 ]'
check "notifier failure is logged" 'grep -q "ALERT EMAIL FAILED" "$T/o5"'

# 6) no temp files left behind
check "temp files cleaned up" '[ -z "$(find "${TMPDIR:-/tmp}" -maxdepth 1 -name "candles-cron.*" -newer "$T/notify" 2>/dev/null)" ]'

echo
if [ "$fails" -eq 0 ]; then echo "cron-wrapper: all tests passed"; else echo "cron-wrapper: $fails FAILED"; exit 1; fi
