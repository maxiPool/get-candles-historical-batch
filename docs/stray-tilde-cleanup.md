# Stray `~` directory — verification and delete proposal (2026-11-01)

Location: `/home/max/dev/projects/get-candles-historical-batch/~` (literal name, untracked in git, 143 MB, 1083 files).

## Contents
- `Documents/all_candles/candles-monthly/` and `Documents/all_candles_copy/candles-monthly/`: 541 files each (540 CSV + `a_lock_file.txt`).
- CSVs are named `SYM_TF_YYYY_MM.csv` (legacy underscore style), months 2025-06..2025-12, M1 and M15. Origin: an old run where `~` was not expanded.

## Verification
Byte-identical comparison is impossible: master files use `SYM-TF-YYYY_MM.csv` and the stray ones are partial (e.g. AUD_NZD M15 2025_10 starts 2025-10-09, 1546 lines vs 2197 in master).
So the check is row-level: every data line of each stray CSV (exact string) must exist in the master file `/home/max/data/documents/all_candles/candles-monthly/SYM/TF/SYM-TF-YYYY_MM.csv`.

Result (scripts: `~/tmp/verify_rows.py`, `~/tmp/verify_stray.sh`; per-file table in `docs/stray-tilde-verification.tsv`):
- 1082 CSVs: **1082 ROWS_SUBSET_OK**, 0 missing rows, 0 value differences, 0 without a master file.
- 1 non-CSV: `a_lock_file.txt` (sha256 490d8124…3e41), a lock marker, not candle data (both copies identical to each other, no master counterpart).
- Master was compared live; the snapshot `/home/max/data/documents/candles-snapshots-20261031/{documents,documents_copy}` was not separately re-hashed in the row check. Run the optional snapshot check below before the delete if the owner wants it.

Optional snapshot check:
`diff -rq /home/max/data/documents/all_candles/candles-monthly /home/max/data/documents/candles-snapshots-20261031/documents/candles-monthly | head`

## Proposed delete (NOT executed, needs owner OK — irreversible)
```
rm -rf -- '/home/max/dev/projects/get-candles-historical-batch/~'
```
The quoted absolute path and `--` matter: an unquoted `~` expands to `$HOME`. Preview first: `du -sh -- '/home/max/dev/projects/get-candles-historical-batch/~'` (expect 143M, 1083 files).
Root cause not fixed here: some config path starting with `~/` is taken literally (not read: `.env`/credentials were not opened). Suggest checking `copyOutputPath` / `outputPath` in the profile config for a `~`.
