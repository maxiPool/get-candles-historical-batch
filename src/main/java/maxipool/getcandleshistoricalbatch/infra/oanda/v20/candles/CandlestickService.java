package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.Instrument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.csv.CsvCandle;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static java.lang.Runtime.getRuntime;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.*;
import static maxipool.getcandleshistoricalbatch.common.csv.CsvUtil.*;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.getLastLineFromCsvCandleFile;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.appendStringToFile;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.writeToFileThatDoesntExist;
import static maxipool.getcandleshistoricalbatch.common.log.LogFileUtil.logToFile;
import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandlestickService {
  public static final int MAX_CANDLE_COUNT_OANDA_API = 5_000;
  public static final ZoneId ZONE_TORONTO = ZoneId.of("America/Toronto");

  private static final List<CandlestickGranularity> GRANULARITY_LIST = List.of(M15, M1);
  private static final Pattern YYYY_MM_REGEXP = Pattern.compile("-(\\d{4})_(\\d{2})\\.csv$");

  private final InstrumentsService instrumentsService;
  private final OandaRestResource oandaRestResource;
  private final CandlestickMapper candlestickMapper;
  private final V20Properties v20Properties;

  /**
   * @param instrument
   * @param granularity
   */
  private record IG(String instrument, CandlestickGranularity granularity) {
    @Override
    public String toString() {
      return "%s-%s".formatted(instrument, granularity);
    }
  }

  public boolean getOandaHistoricalMarketData() {
    var instruments = instrumentsService.findAll();
    log.info("Found {} instruments on Oanda", instruments.size());

    var latestFilesByInstrumentAndGranularity = getLatestFilesByInstrumentAndGranularity(instruments);

    try (var executorService = newFixedThreadPool(getRuntime().availableProcessors())) {
      var result = latestFilesByInstrumentAndGranularity
          .entrySet().stream()
          .map(e -> supplyAsync(() -> e
                  .getValue()
                  .map(p -> entry(e.getKey(), handleExistingFile(e.getKey(), p)))
                  .orElseGet(() -> entry(e.getKey(), handleNoExistingFile(e.getKey()))),
              executorService))
          .collect(collectingAndThen(toList(), fs -> fs.stream().map(CompletableFuture::join)))
          .collect(toMap(Entry::getKey, Entry::getValue));

      var failedIgs = result.entrySet().stream().filter(i -> !i.getValue()).map(Entry::getKey).map(IG::toString).toList();
      if (!failedIgs.isEmpty()) {
        log.info("Failed to update data for: {}", failedIgs);
        return false;
      }
      return true;
    }
  }

  private Map<IG, Optional<Path>> getLatestFilesByInstrumentAndGranularity(List<Instrument> instruments) {
    return instruments
        .stream()
        .map(i -> i.getName().toString())
        .flatMap(i -> GRANULARITY_LIST.stream().map(g -> new IG(i, g)))
        // Convert each instrument into a stream of the matching files in its folder
        .flatMap(ig -> {
          var instrument = ig.instrument();
          var granularity = ig.granularity().toString();

          var subDir = Paths.get(v20Properties.candlestick().outputPath(), instrument, granularity);
          if (!Files.isDirectory(subDir)) {
            log.warn("No folder for '{}' / '{}'. Skipping.", instrument, granularity);
            return Stream.empty();
          }

          try (var files = Files.list(subDir)) {
            List<Entry<IG, Path>> list = files
                .filter(Files::isRegularFile)
                .filter(path -> isMatchingFile(path, instrument, granularity))
                .map(path -> entry(ig, path))
                .toList();
            return list.stream();
          } catch (IOException e) {
            log.warn("IOException", e);
            return Stream.empty();
          }
        })
        // pick the latest file by YearMonth from the filename.
        .collect(groupingBy(
            Entry::getKey,
            maxBy((entry1, entry2) -> {
              var ym1 = parseYearMonthFromFilename(entry1.getValue().getFileName().toString());
              var ym2 = parseYearMonthFromFilename(entry2.getValue().getFileName().toString());
              return ym1.compareTo(ym2);
            })
        ))
        .entrySet().stream()
        .collect(toMap(Entry::getKey, e -> e.getValue().map(Entry::getValue)));
  }

  /**
   * Calls Oanda API to get candles by count and saves them to the corresponding monthly files.
   */
  private boolean handleNoExistingFile(IG ig) {
    var instrument = ig.instrument();
    var granularity = ig.granularity().toString();

    // Create the subfolder structure if needed
    var subDir = Paths.get(v20Properties.candlestick().outputPath(), instrument, granularity);
    try {
      Files.createDirectories(subDir);
    } catch (IOException e) {
      log.warn("Cannot create directory '{}'", subDir, e);
      return false;
    }

    var response = (GetCandlesResponse) null;
    try {
      response = oandaRestResource.getCandlesWithCount(instrument, ig.granularity, MAX_CANDLE_COUNT_OANDA_API);
    } catch (Exception e) {
      var msg = "%nError while trying to get candles from COUNT for %s".formatted(instrument);
      log.error(msg);
      logToFile(msg);
      return false;
    }
    var candlesByYM = getCandlesByYM(response);

    // Write each group to <instrument>_<granularity>_YYYY_MM.csv
    return candlesByYM
        .entrySet().stream()
        .map(e -> {
          var filename = String.format("%s_%s_%04d_%02d.csv",
              instrument, granularity, e.getKey().getYear(), e.getKey().getMonthValue());
          var outPath = subDir.resolve(filename);
          try {
            writeToFileThatDoesntExist(outPath, candlesToCsvWithHeader(e.getValue()));
            return true;
          } catch (IOException ioException) {
            log.warn("Error while appending candles to file: {}", filename, ioException);
            return false;
          }
        })
        .reduce(true, (acc, next) -> acc && next);
  }

  private Map<YearMonth, List<CsvCandle>> getCandlesByYM(GetCandlesResponse response) {
    return response
        .getCandles().stream()
        .filter(Candlestick::getComplete)
        .map(candlestickMapper::oandaCandleToCsvCandle)
        .collect(groupingBy(c -> YearMonth.from(c.getTime())));
  }

  private boolean handleExistingFile(IG ig, Path latestFile) {
    var instrument = ig.instrument();
    var granularity = ig.granularity().toString();

    // 1) Get the last timestamp from the last line of `latestFile`
    var lastLine = getLastLineFromCsvCandleFile(latestFile);
    var candle = csvStringWithoutHeaderToCsvCandlePojo(lastLine);
    var lastTime = ofNullable(candle)
        .flatMap(i -> ofNullable(i.getTime()))
        .map(i -> Instant.parse(i.format(YMDHMS_FORMATTER)))
        .map(i -> i.plus(granularityToSeconds(ig.granularity()), SECONDS))
        .orElse(null);
    if (lastTime == null) {
      return false;
    }

    // 2) Call Oanda from lastTs to now
    var response = (GetCandlesResponse) null;
    try {
      response = getCandlesFromTime(instrument, ig.granularity(), lastTime);
    } catch (Exception ignored) {
      var msg = "%nError while trying to get candles from time for %s; will try getting it using count".formatted(instrument);
      log.error(msg);
      logToFile(msg);
      try {
        response = oandaRestResource.getCandlesWithCount(instrument, ig.granularity, MAX_CANDLE_COUNT_OANDA_API);
      } catch (Exception ignored2) {
        var msg2 = "%nError while trying to get candles from COUNT for %s".formatted(instrument);
        log.error(msg2);
        logToFile(msg2);
        return false;
      }
    }

    // 3) Group by year/month
    var candlesByYM = getCandlesByYM(response);

    // 4) For the year/month that matches `latestFile` => we append
    //    If there's a new year/month => create a new file
    var latestFileYM = parseYearMonthFromFilename(latestFile.getFileName().toString());

    // Directory: e.g. F:\candles\instrument\granularity
    var subDir = latestFile.getParent();

    return candlesByYM
        .entrySet().stream()
        .map(e -> {
          var filename = String.format("%s-%s-%04d_%02d.csv",
              instrument, granularity, e.getKey().getYear(), e.getKey().getMonthValue());
          var outPath = subDir.resolve(filename);

          try {
            if (e.getKey().equals(latestFileYM)) {
              // Same month as the latestFile => append
              appendStringToFile(outPath, candlesToCsvWithoutHeader(e.getValue()));
            } else {
              log.warn("Creating new file: {}", outPath);
              writeToFileThatDoesntExist(outPath, candlesToCsvWithHeader(e.getValue()));
            }
          } catch (IOException ioException) {
            log.error("Failed writing to {}", outPath, ioException);
            return false;
          }
          return true;
        })
        .reduce(true, (acc, next) -> acc && next);
  }

  /**
   * Check if filename looks like: instrument-granularity-yyyy_MM.csv
   * Example: "AUD_CAD-M1-2022_12.csv"
   */
  private static boolean isMatchingFile(Path path, String instrument, String granularity) {
    var fileName = path.getFileName().toString();
    var regex = String.format("^%s-%s-(\\d{4})_(\\d{2})\\.csv$", instrument, granularity);
    return fileName.matches(regex);
  }

  /**
   * Parse the yyyy_MM portion from a filename like:
   * "AUD_CAD-M1-2022_12.csv" -> YearMonth.of(2022, 12)
   */
  private static YearMonth parseYearMonthFromFilename(String fileName) {
    var m = YYYY_MM_REGEXP.matcher(fileName);
    if (m.find()) {
      int year = Integer.parseInt(m.group(1));
      int month = Integer.parseInt(m.group(2));
      return YearMonth.of(year, month);
    } else {
      throw new IllegalArgumentException("Filename does not contain valid yyyy_MM: " + fileName);
    }
  }


  public void logLastCandleTimesBreakdown(/*List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList*/) {
//    var lastCandleTimes = instrumentCandleRequestInfoList
//        .stream()
//        .collect(groupingBy(InstrumentCandleRequestInfo::dateTime,
//            mapping(i -> "%s-%s".formatted(i.instrument().getName().toString(), i.granularity().name()), toList())));
//
//    log.info("Last candle times breakdown");
//    var message = lastCandleTimes
//        .keySet().stream()
//        .sorted()
//        .map(d -> d.format(YMDHMS_FORMATTER))
//        .collect(joining("\n"));
//    log.info(message);
//    logToFile("%n%nLast candle times breakdown as of %s%n%s%n".formatted(ZonedDateTime.now(ZONE_TORONTO), message));
  }


  private void logGetCandlesFromApiBreakdown(/*List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList,*/
      List<EGetCandlesState> getCandlesStates) {
//    var nbOfDistinctInstruments =
//        getNbOfDistinctKeys(instrumentCandleRequestInfoList, InstrumentCandleRequestInfo::instrument);
//    var nbOfDistinctGranularities =
//        getNbOfDistinctKeys(instrumentCandleRequestInfoList, InstrumentCandleRequestInfo::granularity);
//
//    var msg1 = "Get candles done for %d/%d files (%d instruments on %d granularity levels)".formatted(
//        getCandlesStates.stream().filter(s -> s != ERROR).count(),
//        instrumentCandleRequestInfoList.size(),
//        nbOfDistinctInstruments,
//        nbOfDistinctGranularities);
//    var msg2 = "Breakdown: %s".formatted(getCandlesStates.stream().collect(groupingBy(s -> s, counting())));
//    log.info(msg1);
//    log.info(msg2);
//    logToFile("%s%n%s".formatted(ZonedDateTime.now(ZONE_TORONTO), msg1));
//    logToFile("%n%n%s%n%s".formatted(ZonedDateTime.now(ZONE_TORONTO), msg2));
  }

  public GetCandlesResponse getCandlesFromTime(String instrument, CandlestickGranularity granularity, Instant fromTime) {
    return oandaRestResource
        .getCandlesFromTo(
            instrument,
            granularity,
            YMDHMS_FORMATTER.format(fromTime),
            YMDHMS_FORMATTER.format(Instant.now().minus(10, SECONDS)));
    // there seems to be a delay in 'to' time on Oanda server; the minus 10 seconds is to mitigate the error message: "to is in the future"
  }

  /**
   * During business days, verifies if the next candle should be complete. Doesn't check for weekends.
   *
   * @param fromTime example granularity M15, lastCandleTime = 8:00:00; fromTime = 8:15:00;  now = 8:14:00 --> abort get candles API call
   */
  private static boolean isNextCandleComplete(Instrument instrument, CandlestickGranularity granularity, Instant fromTime) {
    var to = Instant.now();
    if (to.isBefore(fromTime.plus(15, MINUTES)) /* server can have a 15 minutes delay */) {
      log.debug("Abort get candles from time: no new candle ready for instrument {} with granularity {}",
          instrument.getName().toString(), granularity);
      return false;
    }
    return true;
  }

  /**
   * Maximum candle count is 5000. Any granularity seems fine.
   */
  public GetCandlesResponse getCandlestickWithCount(String instrument, CandlestickGranularity granularity, int count) {
    return oandaRestResource.getCandlesWithCount(instrument, granularity, count);
  }

  private static long granularityToSeconds(CandlestickGranularity granularity) {
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

  private static <S, T> long getNbOfDistinctKeys(List<S> sList, Function<S, T> keyExtractor) {
    return sList
        .stream()
        .map(keyExtractor)
        .distinct()
        .count();
  }

}
