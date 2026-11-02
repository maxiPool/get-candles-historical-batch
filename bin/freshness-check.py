#!/usr/bin/env python3
"""Daily watchdog: email when the candles batch has not succeeded for 2 or more weekdays.

Reads the success marker the batch writes after every successful full run (first token = epoch seconds).
Weekdays are counted in Toronto time; today counts once the daily runs are over (after 17:30).
Exit codes: 0 fresh, 2 stale and alert sent (or --dry-run), 1 stale but the alert could not be sent.

Usage: freshness-check.py [--marker PATH] [--min-missed 2] [--dry-run]
"""
import argparse
import datetime as dt
import os
import sys
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import notify_email  # noqa: E402

TORONTO = ZoneInfo("America/Toronto")
RUNS_DONE_AT = dt.time(17, 30)
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_MARKER = os.path.join(REPO, "last-success.txt")


def read_last_success(path):
    """Returns an aware datetime, or None when the marker is missing or unreadable."""
    try:
        with open(path) as f:
            first = f.read().split()
        return dt.datetime.fromtimestamp(int(first[0]), tz=dt.timezone.utc)
    except (OSError, ValueError, IndexError):
        return None


def missed_weekdays(last_success, now):
    """Weekdays after the last success's day up to today (today only once the daily runs are done)."""
    last_day = last_success.astimezone(TORONTO).date()
    local_now = now.astimezone(TORONTO)
    end = local_now.date() if local_now.time() >= RUNS_DONE_AT else local_now.date() - dt.timedelta(days=1)
    n, d = 0, last_day + dt.timedelta(days=1)
    while d <= end:
        if d.weekday() < 5:
            n += 1
        d += dt.timedelta(days=1)
    return n


def evaluate(marker, now, min_missed):
    """Returns (stale, message)."""
    last = read_last_success(marker)
    if last is None:
        return True, "No readable success marker at %s: the candles batch has no recorded successful run." % marker
    missed = missed_weekdays(last, now)
    if missed >= min_missed:
        return True, ("The candles batch has not succeeded for %d weekdays (last success %s Toronto time; marker %s).\n"
                      "Check cron.log and the failure emails." % (
                          missed, last.astimezone(TORONTO).strftime("%Y-%m-%d %H:%M"), marker))
    return False, "fresh: last success %s, %d weekday(s) missed" % (last.isoformat(), missed)


def main(argv=None, now=None, notifier=notify_email.notify):
    p = argparse.ArgumentParser()
    p.add_argument("--marker", default=os.environ.get("CANDLES_SUCCESS_MARKER", DEFAULT_MARKER))
    p.add_argument("--min-missed", type=int, default=2)
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args(argv)
    now = now or dt.datetime.now(dt.timezone.utc)
    stale, message = evaluate(a.marker, now, a.min_missed)
    stamp = now.astimezone(TORONTO).isoformat(timespec="seconds")
    if not stale:
        print("%s freshness-check: %s" % (stamp, message))
        return 0
    print("%s freshness-check: STALE: %s" % (stamp, message))
    if a.dry_run:
        return 2
    try:
        notifier("no successful run for 2+ weekdays", message)
    except Exception as e:
        print("freshness-check: FAILED to send alert: %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main())
