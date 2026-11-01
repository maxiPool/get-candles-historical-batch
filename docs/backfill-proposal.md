# Backfill proposal: mid-August 2026 → today (2026-11-01)

## Gap
Master (`candles-monthly`) ends at 2026-08-12 ~20:59Z (AUD_NZD, EUR_USD checked, M15 and M1); 123 of 125 instrument folders have 2026_08 M15 files. About 80 days are missing per instrument/granularity.

## Why the current code can't do it
`CandlestickService.handleExistingFile` → `getCandlesFromTime` makes ONE `from`/`to` call (`OandaRestResource.getCandlesFromTo`). Oanda caps a response at 5000 candles: M15 ≈ 52 days (2 chunks needed), M1 ≈ 3.5 days (~23 chunks). On exception it falls back to `count=5000`, which only returns the latest ~3.5 days of M1 and silently leaves a hole.

## Design
1. New method `getCandlesChunked(instrument, granularity, from, to)` in `CandlestickService`: loop `cursor=from`; request `[cursor, min(cursor+span, to))` with `span = 4500 * granularitySeconds` (margin under 5000); concat; advance `cursor` to last returned candle time + 1 granule (or to chunk end if empty, e.g. weekend). Stop when `cursor >= to`.
2. Keep the existing `to = now - 10s` rule; keep `Candlestick::getComplete` filter and `getCandlesByYM`.
3. `handleExistingFile` calls the chunked method instead of `getCandlesFromTime`; drop the silent count fallback when the gap exceeds one chunk (fail loud + email, as 6eee6d8 does for empty results).
4. Write path unchanged (append to the latest month file, new month files via `writeToFileThatDoesntExist`, then `CopyFileUtil`). Multiple new months per run already supported by the group-by-month loop.
5. De-duplicate on append: drop candles with time <= last line time (guards re-runs and chunk overlap).
6. Throttle: Oanda allows ~100 req/s; 125 instruments × ~25 requests ≈ 3100 requests — add a small per-thread delay and retry/backoff on 429/5xx.
7. Tests: unit test for chunk boundaries with a stubbed `OandaRestResource` (gap of 80 days M1/M15, empty weekend chunk, 5000-cap hit, dedupe); extend `CandlestickServiceFilesTest`.

## Rollout
- Snapshot first: `cp -a` of `candles-monthly` (a snapshot exists from 2026-10-31, still valid for rollback).
- Dry run on one instrument (EUR_USD) with the owner's local profile, check row counts per month and timestamps contiguous (except weekends), then run all.
- Credentials are the owner's: the run uses `--spring.profiles.active=local`; this proposal did not read `.env` or any credential file.
- The cron log shows daily runs skip weekends and succeed without new data since Aug; investigate why the daily run did not close the gap (separate item).
