package maxipool.getcandleshistoricalbatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.util.Set;

/**
 * @param disableOnDays           days on which the batch exits immediately (exit 0)
 * @param repoDir                 git checkout the deployed jar must match (stale-jar check)
 * @param buildCommit             commit the jar was built from; filled at build time by {@code -Dgit.commit=...}
 * @param staleJarCheck           false disables the stale-jar check (tests, manual runs from an IDE)
 * @param staleAfterTradingHours  a pair whose last candle is older than this many market hours is stale
 * @param maxPairsWithoutFile     more pairs without any monthly file than this means a wrong or wiped data folder
 * @param onlyInstruments         restrict the run to these instruments (backfill dry runs); empty = all
 * @param dryRun                  download and report, but write nothing
 * @param successMarkerPath       file written after every successful full run; read by bin/freshness-check.py
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Set<DayOfWeek> disableOnDays,
                            String repoDir,
                            String buildCommit,
                            Boolean staleJarCheck,
                            Integer staleAfterTradingHours,
                            Integer maxPairsWithoutFile,
                            Set<String> onlyInstruments,
                            Boolean dryRun,
                            String successMarkerPath) {

  public AppProperties {
    disableOnDays = disableOnDays == null ? Set.of() : disableOnDays;
    staleJarCheck = staleJarCheck == null || staleJarCheck;
    staleAfterTradingHours = staleAfterTradingHours == null ? 24 : staleAfterTradingHours;
    maxPairsWithoutFile = maxPairsWithoutFile == null ? 4 : maxPairsWithoutFile;
    onlyInstruments = onlyInstruments == null ? Set.of() : onlyInstruments;
    dryRun = dryRun != null && dryRun;
  }

  /** A filtered or dry run is not a full run: it never updates the lock timestamp or the success marker. */
  public boolean isFullRun() {
    return onlyInstruments.isEmpty() && !dryRun;
  }
}
