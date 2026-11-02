package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.Instrument;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.AppProperties;
import maxipool.getcandleshistoricalbatch.common.csv.CsvCandle;
import maxipool.getcandleshistoricalbatch.common.file.CleanupUtil;
import maxipool.getcandleshistoricalbatch.common.file.CopyFileUtil;
import maxipool.getcandleshistoricalbatch.common.time.TradingTime;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static java.lang.Runtime.getRuntime;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Comparator.comparing;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static maxipool.getcandleshistoricalbatch.common.csv.CsvUtil.*;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.getLastLineFromCsvCandleFile;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.appendStringToFile;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.writeToFileThatDoesntExist;
import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;

@Slf4j
@Service
public class CandlestickService {
  private static final int MAX_CANDLE_COUNT_OANDA_API = 5_000;
  private static final ZoneId ZONE_TORONTO = ZoneId.of("America/Toronto");
  private static final List<CandlestickGranularity> GRANULARITY_LIST = List.of(M15, M1);
  private static final Pattern YYYY_MM_REGEXP = Pattern.compile("[-_](\\d{4})_(\\d{2})\\.csv$");
  /** ~50 requests/s overall; Oanda allows ~100. */
  private static final long MIN_REQUEST_INTERVAL_MS = 20;
  private static final int MAX_LISTED = 20;

  private final InstrumentsService instrumentsService;
  private final OandaRestResource oandaRestResource;
  private final CandlestickMapper candlestickMapper;
  private final V20Properties v20Properties;
  private final AppProperties appProperties;
  private final Clock clock;
  private final ChunkedCandleFetcher fetcher;
  private final AtomicInteger progress = new AtomicInteger(0);

  @Autowired
  public CandlestickService(InstrumentsService instrumentsService,
                            OandaRestResource oandaRestResource,
                            CandlestickMapper candlestickMapper,
                            V20Properties v20Properties,
                            AppProperties appProperties) {
    this(instrumentsService, oandaRestResource, candlestickMapper, v20Properties, appProperties,
        Clock.systemUTC(), new ChunkedCandleFetcher(oandaRestResource, Thread::sleep, MIN_REQUEST_INTERVAL_MS));
  }

  CandlestickService(InstrumentsService instrumentsService,
                     OandaRestResource oandaRestResource,
                     CandlestickMapper candlestickMapper,
                     V20Properties v20Properties,
                     AppProperties appProperties,
                     Clock clock,
                     ChunkedCandleFetcher fetcher) {
    this.instrumentsService = instrumentsService;
    this.oandaRestResource = oandaRestResource;
    this.candlestickMapper = candlestickMapper;
    this.v20Properties = v20Properties;
    this.appProperties = appProperties;
    this.clock = clock;
    this.fetcher = fetcher;
  }

  /**
   * @param instrument
   * @param granularity
   */
  record IG(String instrument, CandlestickGranularity granularity) {
    @Override
    public String toString() {
      return "%s-%s".formatted(instrument, granularity);
    }
  }

  /**
   * Outcome of one pair.
   *
   * @param error        null on success
   * @param newCandles   candles written (or that would be written, in a dry run)
   * @param lastCandle   time of the newest candle on disk after the run (null when unknown)
   * @param alreadyFresh the file already held a candle from the last hour, nothing was requested
   */
  record PairResult(IG ig, String error, long newCandles, Instant lastCandle, boolean alreadyFresh) {
    static PairResult failed(IG ig, String error) {
      return new PairResult(ig, error, 0, null, false);
    }

    boolean ok() {
      return error == null;
    }
  }

  /**
   * @param problems empty on success; each entry is one reason to fail loudly
   */
  public record RunReport(List<String> problems, long newCandles, int pairs) {
    public boolean ok() {
      return problems.isEmpty();
    }
  }

  /**
   * Data folder sanity before anything is locked, created or downloaded.
   *
   * @return a problem, or empty when the folder exists and holds at least one instrument folder
   */
  public static Optional<String> checkDataFolder(String outputPath) {
    if (outputPath == null || outputPath.isBlank()) {
      return Optional.of("Data folder is not configured (infra.oanda.v20.candlestick.outputPath)");
    }
    var dir = Paths.get(outputPath);
    if (!Files.isDirectory(dir)) {
      return Optional.of("Data folder is missing: %s (it is never created automatically)".formatted(outputPath));
    }
    try (var entries = Files.list(dir)) {
      if (entries.noneMatch(Files::isDirectory)) {
        return Optional.of("Data folder is empty (no instrument folders): %s".formatted(outputPath));
      }
    } catch (IOException e) {
      return Optional.of("Data folder is unreadable: %s (%s)".formatted(outputPath, e.getMessage()));
    }
    return Optional.empty();
  }

  public RunReport getOandaHistoricalMarketData() {
    var problems = new ArrayList<String>();
    var outputPath = v20Properties.candlestick().outputPath();

    var instruments = filterInstruments(instrumentsService.findAll(), problems);
    log.info("Processing {} instruments{}{}", instruments.size(),
        appProperties.onlyInstruments().isEmpty() ? "" : " (only " + appProperties.onlyInstruments() + ")",
        appProperties.dryRun() ? " [DRY RUN: nothing is written]" : "");
    if (!problems.isEmpty()) {
      return new RunReport(problems, 0, 0);
    }

    var igs = toIgs(instruments);
    var latestFiles = findLatestFiles(outputPath, igs);
    var withoutFile = latestFiles.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(e -> e.getKey().toString()).sorted().toList();
    var matched = igs.size() - withoutFile.size();
    if (matched == 0) {
      problems.add("0 matched files: none of the %d instrument/granularity pairs has a monthly file in %s; nothing was downloaded"
          .formatted(igs.size(), outputPath));
      return new RunReport(problems, 0, igs.size());
    }
    if (withoutFile.size() > appProperties.maxPairsWithoutFile()) {
      problems.add("%d of %d pairs have no monthly file in %s (max %d; wrong or partly wiped data folder?); nothing was downloaded: %s"
          .formatted(withoutFile.size(), igs.size(), outputPath, appProperties.maxPairsWithoutFile(), limit(withoutFile)));
      return new RunReport(problems, 0, igs.size());
    }
    if (!withoutFile.isEmpty()) {
      log.warn("Seeding {} new pairs by count ({} candles each): {}", withoutFile.size(), MAX_CANDLE_COUNT_OANDA_API, withoutFile);
    }

    var results = runAll(latestFiles);
    return summarize(results, problems);
  }

  private List<Instrument> filterInstruments(List<Instrument> all, List<String> problems) {
    var only = appProperties.onlyInstruments();
    if (only.isEmpty()) {
      return all;
    }
    var names = all.stream().map(i -> i.getName().toString()).toList();
    var unknown = only.stream().filter(o -> !names.contains(o)).sorted().toList();
    if (!unknown.isEmpty()) {
      problems.add("Unknown instruments in app.only-instruments: %s".formatted(unknown));
    }
    return all.stream().filter(i -> only.contains(i.getName().toString())).toList();
  }

  private static List<IG> toIgs(List<Instrument> instruments) {
    return instruments
        .stream()
        .map(i -> i.getName().toString())
        .flatMap(i -> GRANULARITY_LIST.stream().map(g -> new IG(i, g)))
        .toList();
  }

  private List<PairResult> runAll(Map<IG, Optional<Path>> latestFiles) {
    var total = latestFiles.size();
    progress.set(0);
    try (var executorService = newFixedThreadPool(Math.max(1, getRuntime().availableProcessors()))) {
      var futures = latestFiles.entrySet().stream()
          .map(e -> supplyAsync(() -> safely(e.getKey(), e.getValue()), executorService))
          .toList();
      return futures.stream().map(CompletableFuture::join).peek(r -> logProgress(total)).toList();
    }
  }

  private PairResult safely(IG ig, Optional<Path> latestFile) {
    try {
      return latestFile.map(p -> handleExistingFile(ig, p)).orElseGet(() -> handleNoExistingFile(ig));
    } catch (RuntimeException e) {
      log.error("{} failed", ig, e);
      return PairResult.failed(ig, e.getMessage());
    }
  }

  private RunReport summarize(List<PairResult> results, List<String> problems) {
    var now = clock.instant();
    var failed = results.stream().filter(r -> !r.ok()).map(r -> r.ig() + ": " + r.error()).sorted().toList();
    if (!failed.isEmpty()) {
      problems.add("Failed download/write for %d pairs: %s".formatted(failed.size(), limit(failed)));
    }

    var newCandles = results.stream().mapToLong(PairResult::newCandles).sum();
    var anyFresh = results.stream().anyMatch(PairResult::alreadyFresh);
    var day = now.atZone(ZONE_TORONTO).getDayOfWeek();
    var weekday = day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    if (weekday && newCandles == 0 && !anyFresh && failed.isEmpty()) {
      problems.add("0 new candles on a weekday (%s) across %d pairs".formatted(day, results.size()));
    }

    var maxHours = appProperties.staleAfterTradingHours();
    var stale = results.stream()
        .filter(PairResult::ok)
        .filter(r -> r.lastCandle() == null || TradingTime.tradingDurationBetween(r.lastCandle(), now).toHours() > maxHours)
        .map(r -> r.ig() + " last=" + (r.lastCandle() == null ? "none" : YMDHMS_FORMATTER.format(r.lastCandle())))
        .sorted()
        .toList();
    if (!stale.isEmpty()) {
      problems.add("Stale data (> %d market hours old) after the run for %d pairs: %s".formatted(maxHours, stale.size(), limit(stale)));
    }

    log.info("Run summary: {} pairs, {} new candles, {} failed, {} stale{}", results.size(), newCandles, failed.size(),
        stale.size(), appProperties.dryRun() ? " [DRY RUN]" : "");
    return new RunReport(problems, newCandles, results.size());
  }

  private static String limit(Collection<String> items) {
    var shown = items.stream().limit(MAX_LISTED).toList();
    return items.size() > MAX_LISTED ? shown + " ... (+" + (items.size() - MAX_LISTED) + " more)" : shown.toString();
  }

  private void logProgress(int total) {
    var count = progress.incrementAndGet();
    if (count % 25 == 0) {
      log.info("{}/{} pairs processed", count, total);
    }
  }

  /**
   * Returns exactly one entry per IG: the latest monthly file, or empty when the folder is missing,
   * unreadable or holds no matching file.
   */
  static Map<IG, Optional<Path>> findLatestFiles(String outputPath, List<IG> igs) {
    return igs.stream().collect(toMap(ig -> ig, ig -> findLatestFile(outputPath, ig)));
  }

  private static Optional<Path> findLatestFile(String outputPath, IG ig) {
    var instrument = ig.instrument();
    var granularity = ig.granularity().toString();
    var subDir = Paths.get(outputPath, instrument, granularity);
    if (!Files.isDirectory(subDir)) {
      return Optional.empty();
    }
    try (var files = Files.list(subDir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> isMatchingFile(path, instrument, granularity))
          .max(comparing((Path p) -> parseYearMonthFromFilename(p.getFileName().toString()))
              // same month in both naming styles: prefer the canonical hyphen one
              .thenComparing(p -> p.getFileName().toString().contains("-" + granularity + "-")));
    } catch (IOException e) {
      log.warn("IOException", e);
      return Optional.empty();
    }
  }

  /**
   * New pair (no monthly file yet): seed it with the latest {@value MAX_CANDLE_COUNT_OANDA_API} candles.
   */
  private PairResult handleNoExistingFile(IG ig) {
    var instrument = ig.instrument();
    var granularity = ig.granularity().toString();
    var subDir = Paths.get(v20Properties.candlestick().outputPath(), instrument, granularity);

    List<Candlestick> raw;
    try {
      var response = oandaRestResource.getCandlesWithCount(instrument, ig.granularity(), MAX_CANDLE_COUNT_OANDA_API);
      raw = response == null || response.getCandles() == null ? List.of() : response.getCandles();
    } catch (Exception e) {
      return PairResult.failed(ig, "download by count failed (new pair): " + e.getMessage());
    }
    var candles = toNewCsvCandles(raw, null);
    if (candles.isEmpty()) {
      return PairResult.failed(ig, "new pair: Oanda returned no complete candles");
    }
    var lastCandle = candles.getLast().getTime().toInstant();
    if (appProperties.dryRun()) {
      logDryRun(ig, candles);
      return new PairResult(ig, null, candles.size(), lastCandle, false);
    }
    try {
      Files.createDirectories(subDir);
    } catch (IOException e) {
      return PairResult.failed(ig, "cannot create directory %s: %s".formatted(subDir, e.getMessage()));
    }
    for (var e : groupByMonth(candles).entrySet()) {
      var filename = canonicalFilename(instrument, granularity, e.getKey());
      var outPath = subDir.resolve(filename);
      try {
        writeToFileThatDoesntExist(outPath, candlesToCsvWithHeader(e.getValue()));
        afterWrite(instrument, granularity, filename, outPath);
      } catch (IOException ioException) {
        return PairResult.failed(ig, "write failed for %s: %s".formatted(outPath, ioException.getMessage()));
      }
    }
    return new PairResult(ig, null, candles.size(), lastCandle, false);
  }

  private PairResult handleExistingFile(IG ig, Path latestFile) {
    var instrument = ig.instrument();
    var granularity = ig.granularity().toString();

    // 1) Last candle on disk
    var candle = csvStringWithoutHeaderToCsvCandlePojo(getLastLineFromCsvCandleFile(latestFile));
    if (candle == null || candle.getTime() == null) {
      return PairResult.failed(ig, "cannot read the last candle of " + latestFile);
    }
    var lastOnDisk = Instant.parse(candle.getTime().format(YMDHMS_FORMATTER));
    var from = lastOnDisk.plus(granularityToSeconds(ig.granularity()), SECONDS);
    var now = clock.instant();
    if (now.minus(1, HOURS).isBefore(from)) {
      log.info("Skipping {} since last candle was within the last hour", ig);
      return new PairResult(ig, null, 0, lastOnDisk, true);
    }

    // 2) Chunked download from the last candle to now; any failed chunk fails the pair (no count fallback)
    // there seems to be a delay in 'to' time on Oanda server; the minus 10 seconds avoids "to is in the future"
    var to = now.minus(10, SECONDS);
    List<Candlestick> raw;
    try {
      raw = fetcher.fetch(instrument, ig.granularity(), from, to);
    } catch (ChunkedCandleFetcher.DownloadException e) {
      return PairResult.failed(ig, e.getMessage());
    }
    var candles = toNewCsvCandles(raw, lastOnDisk);
    if (candles.isEmpty()) {
      return new PairResult(ig, null, 0, lastOnDisk, false);
    }
    var lastCandle = candles.getLast().getTime().toInstant();
    if (appProperties.dryRun()) {
      logDryRun(ig, candles);
      return new PairResult(ig, null, candles.size(), lastCandle, false);
    }

    // 3) Append to the latest month file; new months get new files
    var latestFileYM = parseYearMonthFromFilename(latestFile.getFileName().toString());
    var subDir = latestFile.getParent();
    for (var e : groupByMonth(candles).entrySet()) {
      var sameMonth = e.getKey().equals(latestFileYM);
      // same month: keep writing to the existing file, whichever separator style it uses
      var filename = sameMonth ? latestFile.getFileName().toString() : canonicalFilename(instrument, granularity, e.getKey());
      var outPath = subDir.resolve(filename);
      try {
        if (sameMonth) {
          appendStringToFile(outPath, candlesToCsvWithoutHeader(e.getValue()));
        } else {
          log.warn("Creating new file: {}", outPath);
          writeToFileThatDoesntExist(outPath, candlesToCsvWithHeader(e.getValue()));
        }
        afterWrite(instrument, granularity, filename, outPath);
      } catch (IOException ioException) {
        return PairResult.failed(ig, "write failed for %s: %s".formatted(outPath, ioException.getMessage()));
      }
    }
    return new PairResult(ig, null, candles.size(), lastCandle, false);
  }

  private void afterWrite(String instrument, String granularity, String filename, Path outPath) throws IOException {
    CleanupUtil.cleanup(filename, outPath);
    CopyFileUtil.copyToSecondDisk(outPath, v20Properties.candlestick().copyOutputPath(), instrument, granularity, filename);
  }

  /**
   * Complete candles strictly after {@code lastOnDisk}, sorted and de-duplicated by time
   * (guards re-runs and chunk-boundary overlap).
   */
  private List<CsvCandle> toNewCsvCandles(List<Candlestick> raw, Instant lastOnDisk) {
    var byTime = new TreeMap<Instant, CsvCandle>();
    for (var c : raw) {
      if (!Boolean.TRUE.equals(c.getComplete())) {
        continue;
      }
      var csv = candlestickMapper.oandaCandleToCsvCandle(c);
      var t = csv.getTime().toInstant();
      if (lastOnDisk == null || t.isAfter(lastOnDisk)) {
        byTime.putIfAbsent(t, csv);
      }
    }
    return List.copyOf(byTime.values());
  }

  private static Map<YearMonth, List<CsvCandle>> groupByMonth(List<CsvCandle> candles) {
    return candles.stream().collect(groupingBy(c -> YearMonth.from(c.getTime()), TreeMap::new, toList()));
  }

  private static void logDryRun(IG ig, List<CsvCandle> candles) {
    groupByMonth(candles).forEach((ym, cs) -> log.info("[DRY RUN] {} {}: {} candles, {} .. {}", ig, ym, cs.size(),
        cs.getFirst().getTime(), cs.getLast().getTime()));
  }

  private static String canonicalFilename(String instrument, String granularity, YearMonth ym) {
    return String.format("%s-%s-%04d_%02d.csv", instrument, granularity, ym.getYear(), ym.getMonthValue());
  }

  /**
   * Check if filename looks like: instrument-granularity-yyyy_MM.csv (canonical) or instrument_granularity_yyyy_MM.csv (legacy).
   * Example: "AUD_CAD-M1-2022_12.csv" or "AUD_CAD_M1_2022_12.csv"
   */
  static boolean isMatchingFile(Path path, String instrument, String granularity) {
    var fileName = path.getFileName().toString();
    var regex = String.format("^%s[-_]%s[-_](\\d{4})_(\\d{2})\\.csv$", Pattern.quote(instrument), Pattern.quote(granularity));
    return fileName.matches(regex);
  }

  /**
   * Parse the yyyy_MM portion from a filename like:
   * "AUD_CAD-M1-2022_12.csv" -> YearMonth.of(2022, 12)
   */
  public static YearMonth parseYearMonthFromFilename(String fileName) {
    var m = YYYY_MM_REGEXP.matcher(fileName);
    if (m.find()) {
      int year = Integer.parseInt(m.group(1));
      int month = Integer.parseInt(m.group(2));
      return YearMonth.of(year, month);
    } else {
      throw new IllegalArgumentException("Filename does not contain valid yyyy_MM: " + fileName);
    }
  }

  /**
   * Maximum candle count is 5000. Any granularity seems fine.
   */
  public GetCandlesResponse getCandlestickWithCount(String instrument, CandlestickGranularity granularity, int count) {
    return oandaRestResource.getCandlesWithCount(instrument, granularity, count);
  }

  static long granularityToSeconds(CandlestickGranularity granularity) {
    return switch (granularity) {
      case S5 -> 5;
      case S10 -> 10;
      case S15 -> 15;
      case S30 -> 30;
      case M1 -> 60;
      case M2 -> 120;
      case M4 -> 240;
      case M5 -> 300;
      case M10 -> 600;
      case M15 -> 900;
      case M30 -> 1_800;
      case H1 -> 3_600;
      case H2 -> 7_200;
      case H3 -> 10_800;
      case H4 -> 14_400;
      case H6 -> 21_600;
      case H8 -> 28_800;
      case H12 -> 43_200;
      case D -> 86_400;
      case W -> 604_800; // assuming 7 days
      case M -> throw new UnsupportedOperationException("No seconds for monthly; ambiguous number of days");
    };
  }

}
