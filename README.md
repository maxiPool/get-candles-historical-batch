### Batch job to get historical candles

Dedicated project for the batch job; faster startup, fewer dependencies.

Data: `/home/max/data/documents/all_candles/candles-monthly` (master) and `.../all_candles_copy/candles-monthly`
(copy), one `SYM-TF-YYYY_MM.csv` per instrument, granularity (M1, M15) and month. The batch never creates the
data folder. If it is missing or empty, the run fails.

#### Build, test, deploy

- Tests: `./mvnw test` (Java), `/usr/bin/python3 -m unittest discover -s tests` and `bash tests/test_cron_wrapper.sh` (watchdog)
- Deploy (the jar cron runs): `./bin/deploy.sh`. It builds from a clean HEAD, runs the tests and embeds the commit.
  A jar whose build commit differs from HEAD in `src/` or `pom.xml` refuses to run ("Stale jar").

#### Run

`./get-candles-batch.sh [--app.only-instruments=EUR_USD] [--app.dry-run=true]`

Exit 0 = success, or nothing to do (weekend, recent run). Exit 1 = failure; the reasons are in the log and in one
email. A run fails on: 0 matched files, too many pairs without a file, a failed download (4 attempts, no count
fallback), a missing or empty data folder, 0 new candles on a weekday, stale data (> 24 market hours), or a
stale jar.

#### Cron jobs

| Schedule   | What                                                                     |
|------------|--------------------------------------------------------------------------|
| @reboot    | batch, 60 seconds after startup, through `bin/cron-wrapper.sh`           |
| 0 17 * * * | batch, through `bin/cron-wrapper.sh` (emails on non-zero exit)           |
| 10 17 * * * | retry; exits at once if the 17:00 run succeeded                         |
| 0 18 * * * | `bin/freshness-check.py`: emails after 2 weekdays without a success      |

Output is logged to: /home/max/dev/projects/get-candles-historical-batch/cron.log

Backfill after an outage: `docs/rollout-2026-11-02.md` (dry run on one instrument, check with `bin/check-gaps.py`,
then everything).

Useful commands:
- `crontab -l` — view your cron jobs
- `crontab -e` — edit cron jobs
- `tail -f /home/max/dev/projects/get-candles-historical-batch/cron.log` — watch logs
