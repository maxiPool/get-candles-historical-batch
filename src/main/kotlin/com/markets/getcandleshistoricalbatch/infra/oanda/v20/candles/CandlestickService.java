package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.markets.getcandleshistoricalbatch.common.JsonPrinter;
import com.markets.getcandleshistoricalbatch.common.csv.CsvCandle;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.model.InstrumentCandleRequestInfo;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.markets.getcandleshistoricalbatch.common.csv.CsvUtil.*;
import static com.markets.getcandleshistoricalbatch.common.file.ReadFileUtil.getLastLineFromCsvCandleFile;
import static com.markets.getcandleshistoricalbatch.common.file.WriteFileUtil.appendStringToFile;
import static com.markets.getcandleshistoricalbatch.common.file.WriteFileUtil.writeToFileThatDoesntExist;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.model.EGetCandlesState.*;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument.INSTRUMENT_LIST;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;
import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandlestickService {
  public static final int MAX_CANDLE_COUNT_OANDA_API = 5_000;

  private final V20Properties v20Properties;
  private final OandaRestResource oandaRestResource;
  private final CandlestickMapper candlestickMapper;

  public void runGetNextCandlesBatch() {
    log.info("Launching get candles historical data batch job");
    var granularityList = List.of(M15, M1);

    var candleRequestInfoList = getRequestInfoList(INSTRUMENT_LIST, granularityList);
    logLastCandleTimesBreakdown(candleRequestInfoList);
    getCandlesForMany(candleRequestInfoList);
  }

  private void logLastCandleTimesBreakdown(List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList) {
    var lastCandleTimes = instrumentCandleRequestInfoList
        .stream()
        .collect(groupingBy(InstrumentCandleRequestInfo::dateTime,
            mapping(i -> "%s-%s".formatted(i.instrument().name(), i.granularity().name()), toList())));

    log.info("Last candle times breakdown");
    System.out.println(lastCandleTimes
        .keySet().stream()
        .sorted()
        .map(d -> d.format(YMDHMS_FORMATTER))
        .collect(joining("\n")));
    JsonPrinter.printJson(lastCandleTimes);
  }

  public void getCandlesForMany(List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList) {
    var getCandlesStates = instrumentCandleRequestInfoList
        .stream()
        .map(i -> supplyAsync(() -> getCandlesFor(i)))
        .collect(collectingAndThen(toList(),
            fs -> fs
                .stream()
                .map(CompletableFuture::join)
                .toList()));

    logGetCandlesFromApiBreakdown(instrumentCandleRequestInfoList, getCandlesStates);
  }

  private static void logGetCandlesFromApiBreakdown(List<InstrumentCandleRequestInfo> instrumentCandleRequestInfoList,
                                                    List<EGetCandlesState> getCandlesStates) {
    var nbOfDistinctInstruments =
        getNbOfDistinctKeys(instrumentCandleRequestInfoList, InstrumentCandleRequestInfo::instrument);
    var nbOfDistinctGranularities =
        getNbOfDistinctKeys(instrumentCandleRequestInfoList, InstrumentCandleRequestInfo::granularity);
    log.info("Get candles done for {}/{} files ({} instruments on {} granularity levels)",
        getCandlesStates.stream().filter(s -> s != ERROR).count(),
        instrumentCandleRequestInfoList.size(),
        nbOfDistinctInstruments,
        nbOfDistinctGranularities);
    log.info("Breakdown: {}", getCandlesStates.stream().collect(groupingBy(s -> s, counting())));
  }

  /**
   * Gets the candles for the instrument and appends them to the output path defined in configuration.
   */
  public EGetCandlesState getCandlesFor(InstrumentCandleRequestInfo i) {
    if (Files.notExists(Paths.get(i.outputPath()))) {
      var response = getCandlestickWithCount(i.instrument(), i.granularity(), MAX_CANDLE_COUNT_OANDA_API);
      return SUCCESS == onComplete(response.getCandles(), i.outputPath())
          ? NEW_GET_5K_CANDLES
          : ERROR;
    } else {
      var lastTimePlusGranularity = Instant.parse(i.dateTime().format(YMDHMS_FORMATTER))
          .plus(granularityToSeconds(i.granularity()), SECONDS);

      if (isNextCandleComplete(i.instrument(), i.granularity(), lastTimePlusGranularity)) {
        var response = getCandlesFromTime(i.instrument(), i.granularity(), lastTimePlusGranularity);
        if (response.getCandles().isEmpty()) {
          return NO_NEW_CANDLES;
        }
        return onComplete(response.getCandles(), i.outputPath());
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
    var candlesArray = candles
        .stream()
        .filter(Candlestick::getComplete)
        .map(candlestickMapper::oandaCandleToCsvCandle)
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
  public List<InstrumentCandleRequestInfo> getRequestInfoList(
      List<EInstrument> instrumentList, List<CandlestickGranularity> granularityList) {
    return instrumentList
        .stream()
        .flatMap(i -> granularityList.stream().map(g -> getRequestInfo(i, g)))
        .toList();
  }

  private InstrumentCandleRequestInfo getRequestInfo(EInstrument i, CandlestickGranularity g) {
    var outputPath = v20Properties.candlestick().outputPathTemplate().formatted(i.toString(), g);
    var lastLine = getLastLineFromCsvCandleFile(outputPath);
    var maybeCandle = csvStringWithoutHeaderToCsvCandlePojo(lastLine);
    var dateTime = ofNullable(maybeCandle)
        .map(CsvCandle::getTime)
        .orElse(null);

    return InstrumentCandleRequestInfo
        .builder()
        .granularity(g)
        .instrument(i)
        .outputPath(outputPath)
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
