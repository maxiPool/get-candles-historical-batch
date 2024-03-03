package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.Instrument;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.csv.CsvCandle;
import maxipool.getcandleshistoricalbatch.common.log.LogFileService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model.InstrumentCandleRequestInfo;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static java.util.stream.Collectors.*;
import static maxipool.getcandleshistoricalbatch.common.csv.CsvUtil.*;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.getLastLineFromCsvCandleFile;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.appendStringToFile;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.writeToFileThatDoesntExist;
import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState.*;
import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandlestickService {
  public static final int MAX_CANDLE_COUNT_OANDA_API = 5_000;
  public static final ZoneId ZONE_TORONTO = ZoneId.of("America/Toronto");

  private final InstrumentsService instrumentsService;
  private final OandaRestResource oandaRestResource;
  private final CandlestickMapper candlestickMapper;
  private final LogFileService logFileService;
  private final V20Properties v20Properties;

  public void runGetNextCandlesBatch() {
    log.info("Launching get candles historical data batch job");
    var granularityList = List.of(M15, M1);

    var instruments = instrumentsService.findAll();
    log.info("Found {} instruments on Oanda", instruments.size());
    getCandlesForMany(getRequestInfoList(instruments, granularityList));
    // Get request info list will now have different data since the files are being read to create the request info object
    // and getCandlesForMany just modified the files.
    logLastCandleTimesBreakdown(getRequestInfoList(instruments, granularityList));
  }

  public void logLastCandleTimesBreakdown(List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList) {
    var lastCandleTimes = instrumentCandleRequestInfoList
        .stream()
        .collect(groupingBy(InstrumentCandleRequestInfo::dateTime,
            mapping(i -> "%s-%s".formatted(i.instrument().getName().toString(), i.granularity().name()), toList())));

    log.info("Last candle times breakdown");
    var message = lastCandleTimes
        .keySet().stream()
        .sorted()
        .map(d -> d.format(YMDHMS_FORMATTER))
        .collect(joining("\n"));
    log.info(message);
    logFileService.logToFile("%n%nLast candle times breakdown as of %s%n%s"
        .formatted(ZonedDateTime.now(ZONE_TORONTO), message));
  }

  public void getCandlesForMany(List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList) {
    var getCandlesStates = instrumentCandleRequestInfoList
        .stream()
        .map(i -> supplyAsync(() -> getCandlesFor(i), newVirtualThreadPerTaskExecutor()))
        .collect(collectingAndThen(toList(),
            fs -> fs.stream().map(CompletableFuture::join).map(i -> i.orElse(null)).filter(Objects::nonNull).toList()));

    logGetCandlesFromApiBreakdown(instrumentCandleRequestInfoList, getCandlesStates);
  }

  private void logGetCandlesFromApiBreakdown(List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList,
                                             List<EGetCandlesState> getCandlesStates) {
    var nbOfDistinctInstruments =
        getNbOfDistinctKeys(instrumentCandleRequestInfoList, InstrumentCandleRequestInfo::instrument);
    var nbOfDistinctGranularities =
        getNbOfDistinctKeys(instrumentCandleRequestInfoList, InstrumentCandleRequestInfo::granularity);

    var msg1 = "Get candles done for %d/%d files (%d instruments on %d granularity levels)".formatted(
        getCandlesStates.stream().filter(s -> s != ERROR).count(),
        instrumentCandleRequestInfoList.size(),
        nbOfDistinctInstruments,
        nbOfDistinctGranularities);
    var msg2 = "Breakdown: %s".formatted(getCandlesStates.stream().collect(groupingBy(s -> s, counting())));
    log.info(msg1);
    log.info(msg2);
    logFileService.logToFile("%s%n%s".formatted(ZonedDateTime.now(ZONE_TORONTO), msg1));
    logFileService.logToFile("%n%n%s%n%s".formatted(ZonedDateTime.now(ZONE_TORONTO), msg2));
  }

  /**
   * Gets the candles for the instrument and appends them to the output path defined in configuration.
   */
  public Optional<EGetCandlesState> getCandlesFor(InstrumentCandleRequestInfo i) {
    return i.outputPaths()
        .stream()
        .map(outputPath -> {
          if (Files.notExists(Paths.get(outputPath))) {
            return getWithCount(i, outputPath, Instant.MIN);
          }
          var lastTime = Instant.parse(i.dateTime().format(YMDHMS_FORMATTER));
          var lastTimePlusGranularity = lastTime.plus(granularityToSeconds(i.granularity()), SECONDS);

          if (!isNextCandleComplete(i.instrument(), i.granularity(), lastTimePlusGranularity)) {
            return NEXT_CANDLE_NOT_COMPLETE;
          }

          return getCandlesState(i, outputPath, lastTimePlusGranularity, lastTime);
        })
        .reduce((a, b) -> a);
  }

  private EGetCandlesState getCandlesState(InstrumentCandleRequestInfo i, String outputPath, Instant lastTimePlusGranularity, Instant lastTime) {
    try {
      var response = getCandlesFromTime(i.instrument(), i.granularity(), lastTimePlusGranularity);
      return response.getCandles().isEmpty()
          ? NO_NEW_CANDLES
          : onComplete(response.getCandles(), outputPath, lastTime);
    } catch (Exception e) {
      var msg = "%nError while trying to get candles from time for %s; will try getting it using count".formatted(i.instrument());
      log.error(msg);
      logFileService.logToFile(msg);
      return getWithCount(i, outputPath, lastTime);
    }
  }

  @NotNull
  private EGetCandlesState getWithCount(InstrumentCandleRequestInfo i, String outputPath, Instant lastTime) {
    try {
      var response = getCandlestickWithCount(i.instrument().getName().toString(), i.granularity(), MAX_CANDLE_COUNT_OANDA_API);
      return SUCCESS == onComplete(response.getCandles(), outputPath, lastTime)
          ? NEW_GET_5K_CANDLES
          : ERROR;
    } catch (Exception e) {
      var msg = "%nError while trying to get candles from COUNT for %s".formatted(i.instrument());
      log.error(msg);
      logFileService.logToFile(msg);
      return ERROR;
    }
  }

  public GetCandlesResponse getCandlesFromTime(Instrument instrument, CandlestickGranularity granularity, Instant fromTime) {
    return oandaRestResource
        .getCandlesFromTo(instrument.getName().toString(),
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

  private EGetCandlesState onComplete(List<Candlestick> candles, String filePath, Instant lastTime) {
    var candlesArray = candles
        .stream()
        .filter(Candlestick::getComplete)
        .map(candlestickMapper::oandaCandleToCsvCandle)
        .filter(c -> c.getTime().toInstant().isAfter(lastTime))
        .toArray(CsvCandle[]::new);

    try {
      var path = Paths.get(filePath);
      if (!Files.exists(path)) {
        log.warn("Creating new file: {}", filePath);
        writeToFileThatDoesntExist(filePath, candlesToCsvWithHeader(candlesArray));
      } else {
        appendStringToFile(filePath, candlesToCsvWithoutHeader(candlesArray));
      }
      return SUCCESS;
    } catch (IOException e) {
      log.error("Error while appending candles to file: {}", filePath, e);
      return ERROR;
    }
  }

  @NotNull
  public List<InstrumentCandleRequestInfo> getRequestInfoList(List<Instrument> instruments, List<CandlestickGranularity> granularityList) {
    return instruments
        .stream()
        .flatMap(i -> granularityList.stream().map(g -> getRequestInfo(i, g)))
        .toList();
  }

  private InstrumentCandleRequestInfo getRequestInfo(Instrument i, CandlestickGranularity g) {
    var outputPaths = v20Properties
        .candlestick()
        .outputPathTemplates().stream()
        .map(it -> it.formatted(i.getName().toString(), g))
        .toList();
    var lastLine = getLastLineFromCsvCandleFile(outputPaths.get(0));
    var maybeCandle = csvStringWithoutHeaderToCsvCandlePojo(lastLine);
    var dateTime = ofNullable(maybeCandle)
        .map(CsvCandle::getTime)
        .orElse(null);

    return InstrumentCandleRequestInfo
        .builder()
        .granularity(g)
        .instrument(i)
        .outputPaths(outputPaths)
        .lastLine(lastLine)
        .dateTime(dateTime)
        .build();
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
