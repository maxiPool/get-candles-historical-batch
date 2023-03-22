package com.markets.getcandleshistoricalbatch;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Scanner;

import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService.getLastCandle;
import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument.USD_CAD;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;


@Disabled
@Slf4j
@SpringBootTest
@ActiveProfiles({"local"})
public class DemoCandlesticksTest {

  @Autowired
  private V20Properties v20Properties;

  @Autowired
  private CandlestickService candlestickService;

  @Test
  void should_getCandlesFor() {
    var instrumentToPathMap = stream(EInstrument.values())
        .collect(toMap(i -> i, i -> v20Properties.candlestick().outputPathTemplate().formatted(i.toString().toLowerCase(), M15)));

    var next = instrumentToPathMap.entrySet().iterator().next();

    candlestickService.getCandlesFor(next.getKey(), M15, next.getValue());

    var s = new Scanner(System.in);
    s.nextLine();
  }

  @Test
  void should_getCandlesticksFromOandaAPIWithStartTime() {
    var lastCandle = getLastCandle(getUsdCadFilePath());
    var plusOneMinute = Instant.parse(lastCandle.getTime().toString()).plus(15, MINUTES);

    candlestickService.getCandlesFromTime(USD_CAD, M15, plusOneMinute);

    var s = new Scanner(System.in);
    s.nextLine();
  }

  @Test
  void should_getCandlesticksFromOandaAPIWithCandleCount() {
    candlestickService.getCandlestickWithCount(USD_CAD, M15, 5);

    var s = new Scanner(System.in);
    s.nextLine();
  }

  @Test
  void should_getDateTimeFromLastSavedCandle() {
    var lastCandle = getLastCandle(getUsdCadFilePath());
    log.info("last candle: {}", lastCandle);
  }

  private String getUsdCadFilePath() {
    return v20Properties.candlestick().outputPathTemplate().formatted(USD_CAD, M15);
  }

}
