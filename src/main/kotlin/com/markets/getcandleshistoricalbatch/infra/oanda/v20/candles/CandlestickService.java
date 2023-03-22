package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.CommonUtils;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil.CsvCandle;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil.CsvUtil;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.DateTime;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.ReadFileUtil.FirstAndLastLine;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.ReadFileUtil.getFirstAndLastLineFromFile;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil.CsvCandle.getSchemaHeader;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil.CsvUtil.csvStringToCsvCandlePojo;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState.*;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandlestickService {
  private static final int MAX_CANDLE_COUNT_OANDA_API = 5_000;

  private final V20Properties v20Properties;
  private final OandaRestResource oandaRestResource;
  private final CandlestickMapper candlestickMapper;

  @Builder
  @With
  public record InstrumentCandleRequestInfo(EInstrument instrument,
                                            CandlestickGranularity granularity,
                                            String outputPath,
                                            FirstAndLastLine firstAndLastLine, /* for logs */
                                            DateTime dateTime /* for logs */) {
  }

  public void logLastCandleTimesBreakdown(List<EInstrument> instrumentList, List<CandlestickGranularity> granularityList) {
    var lastCandleTimes = getInstrumentToGranularityToPath(instrumentList, granularityList)
        .stream()
        .map(i -> i.withFirstAndLastLine(getFirstAndLastLineFromFile(i.outputPath())))
        .map(i -> {
          var lastLine = "%s\n%s".formatted(getSchemaHeader(), i.firstAndLastLine().last());
          var maybeCsvCandle = ofNullable(csvStringToCsvCandlePojo(lastLine));
          return i.withDateTime(maybeCsvCandle.map(CsvCandle::getTime).orElse(null));
        })
        .collect(groupingBy(InstrumentCandleRequestInfo::dateTime,
            mapping(i -> "%s-%s".formatted(i.instrument().name(), i.granularity().name()), toList())));

    log.info("Last candle times breakdown");
    CommonUtils.printJson(lastCandleTimes);
  }

  public void getCandlesForMany(List<EInstrument> instrumentList, List<CandlestickGranularity> granularityList) {
    var total = instrumentList.size() * granularityList.size();

    var getCandlesStates = getInstrumentToGranularityToPath(instrumentList, granularityList)
        .stream()
        .map(i -> supplyAsync(() -> getCandlesFor(i.instrument(), i.granularity(), i.outputPath())))
        .collect(collectingAndThen(toList(),
            fs -> fs
                .stream()
                .map(CompletableFuture::join)
                .toList()));

    log.info("Get candles done for {}/{} files ({} instruments on {} granularity levels); breakdown: {}",
        getCandlesStates.stream().filter(s -> s != ERROR).count(),
        total,
        instrumentList.size(),
        granularityList.size(),
        getCandlesStates.stream().collect(groupingBy(s -> s, counting())));

    newSingleThreadScheduledExecutor().schedule(() -> System.exit(0), 1_000, MILLISECONDS);
  }

  public EGetCandlesState getCandlesFor(EInstrument instrument, CandlestickGranularity granularity, String filePath) {
    if (Files.notExists(Paths.get(filePath))) {
      var response = getCandlestickWithCount(instrument, granularity, MAX_CANDLE_COUNT_OANDA_API);
      return SUCCESS == onComplete(response.getCandles(), filePath)
          ? NEW_GET_5K_CANDLES
          : ERROR;
    } else {
      var lastTimePlusGranularity = Instant.parse(getLastCandle(filePath).getTime().toString())
          .plus(granularityToSeconds(granularity), SECONDS);

      if (isNextCandleComplete(instrument, granularity, lastTimePlusGranularity)) {
        var response = getCandlesFromTime(instrument, granularity, lastTimePlusGranularity);
        if (response.getCandles().isEmpty()) {
          return NO_NEW_CANDLES;
        }
        return onComplete(response.getCandles(), filePath);
      }
      return NEXT_CANDLE_NOT_COMPLETE;
    }
  }

  public GetCandlesResponse getCandlesFromTime(EInstrument instrument, CandlestickGranularity granularity, Instant fromTime) {
    return oandaRestResource
        .getCandlesFromTo(instrument,
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
  private static boolean isNextCandleComplete(EInstrument instrument, CandlestickGranularity granularity, Instant fromTime) {
    var to = Instant.now();
    if (to.isBefore(fromTime.plus(15, MINUTES)) /* server can have a 15 minutes delay */) {
      log.debug("Abort get candles from time: no new candle ready for instrument {} with granularity {}",
          instrument, granularity);
      return false;
    }
    return true;
  }

  /**
   * Maximum candle count is 5000. Any granularity seems fine.
   */
  public GetCandlesResponse getCandlestickWithCount(EInstrument instrument, CandlestickGranularity granularity, int count) {
    return oandaRestResource.getCandlesWithCount(instrument, granularity, count);
  }

  private EGetCandlesState onComplete(List<Candlestick> candles, String filePath) {
    var contentToAppend = candles
        .stream()
        .filter(Candlestick::getComplete)
        .map(candlestickMapper::oandaCandleToCsvCandle)
        .map(CsvUtil::candleToCsv)
        .collect(joining(""));

    try {
      var path = Paths.get(filePath);
      if (!Files.exists(path)) {
        log.warn("Creating file that doesn't exist: {}", filePath);
        Files.createFile(path);
        contentToAppend = "%s\n%s".formatted(getSchemaHeader(), contentToAppend);
      }

      log.debug("Writing to file: {}", filePath);
      Files.writeString(
          path,
          contentToAppend,
          StandardOpenOption.APPEND);
      log.debug("Done with file: {}", filePath);
      return SUCCESS;
    } catch (IOException e) {
      log.error("Error while appending candles to file: {}", filePath);
      return ERROR;
    }
  }

  public static CsvCandle getLastCandle(String filePath) {
    var firstAndLastLine = getFirstAndLastLineFromFile(filePath);
    var concat = "%s\n%s".formatted(firstAndLastLine.first(), firstAndLastLine.last());
    return csvStringToCsvCandlePojo(concat);
  }

  @NotNull
  private List<InstrumentCandleRequestInfo> getInstrumentToGranularityToPath(
      List<EInstrument> instrumentList, List<CandlestickGranularity> granularityList) {
    return instrumentList
        .stream()
        .flatMap(i -> granularityList
            .stream()
            .map(g -> InstrumentCandleRequestInfo
                .builder()
                .granularity(g)
                .instrument(i)
                .outputPath(v20Properties.candlestick().outputPathTemplate().formatted(i.toString(), g))
                .build()))
        .toList();
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

}
