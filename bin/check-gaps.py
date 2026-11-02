#!/usr/bin/env python3
"""Post-backfill check for one pair: rows per month, duplicates, order, and gaps while the FX market was open.

Usage: check-gaps.py DATA_ROOT INSTRUMENT GRANULARITY [--since 2026-08-01] [--max-gap-min 30]
Example: check-gaps.py /home/max/data/documents/all_candles/candles-monthly EUR_USD M1 --since 2026-08-12
Read-only. Exit 1 when duplicates, disorder or open-market gaps longer than --max-gap-min are found.
"""
import argparse
import datetime as dt
import glob
import os
import re
import sys
from zoneinfo import ZoneInfo

NY = ZoneInfo("America/New_York")


def market_open_minutes(a, b):
    """Minutes between a and b outside the Fri 17:00 -> Sun 17:00 New York closure (minute resolution)."""
    n, t = 0, a
    while t < b:
        ny = t.astimezone(NY)
        wd, hm = ny.weekday(), (ny.hour, ny.minute)
        closed = (wd == 4 and hm >= (17, 0)) or wd == 5 or (wd == 6 and hm < (17, 0))
        if not closed:
            n += 1
        t += dt.timedelta(minutes=1)
    return n


def main():
    p = argparse.ArgumentParser()
    p.add_argument("root")
    p.add_argument("instrument")
    p.add_argument("granularity")
    p.add_argument("--since", default="1970-01-01")
    p.add_argument("--max-gap-min", type=int, default=30)
    a = p.parse_args()
    since = dt.datetime.fromisoformat(a.since).replace(tzinfo=dt.timezone.utc)
    step = {"M1": 1, "M15": 15}[a.granularity]
    pat = re.compile(r"[-_](\d{4})_(\d{2})\.csv$")
    files = sorted(glob.glob(os.path.join(a.root, a.instrument, a.granularity, "*.csv")),
                   key=lambda f: pat.search(f).groups())
    times, problems = [], 0
    for f in files:
        rows = [l.split(",")[0] for l in open(f).read().splitlines()[1:] if l.strip()]
        ts = [dt.datetime.fromisoformat(r.replace("Z", "+00:00")) for r in rows]
        ts = [t for t in ts if t >= since]
        if ts:
            print("%-40s %6d rows  %s .. %s" % (os.path.basename(f), len(ts), ts[0].isoformat(), ts[-1].isoformat()))
        times += ts
    for prev, cur in zip(times, times[1:]):
        if cur <= prev:
            print("DISORDER/DUPLICATE: %s then %s" % (prev, cur))
            problems += 1
        elif (cur - prev).total_seconds() > step * 60:
            gap = market_open_minutes(prev + dt.timedelta(minutes=step), cur)
            if gap > a.max_gap_min:
                print("GAP: %d open-market minutes missing between %s and %s" % (gap, prev, cur))
                problems += 1
    print("%d rows checked, %d problems" % (len(times), problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
