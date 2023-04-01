package com.markets.getcandleshistoricalbatch;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static java.util.stream.Collectors.joining;


@Component
@RequiredArgsConstructor
@Slf4j
public class OnAppReadyManager {

  private static final ZoneId TORONTO_ZONE = ZoneId.of("America/Toronto");

  private final AppProperties appProperties;
  private final V20Properties v20Properties;
  private final CandlestickService candlestickService;

  @EventListener
  public void onAppReady(ApplicationReadyEvent ignored) throws IOException {
    log.info("[AppReady]");
    exitIfDisabledDays();

    candles();

    log.info("[DONE]");
    System.exit(0);
  }

  private void exitIfDisabledDays() throws IOException {
    var today = ZonedDateTime.now(TORONTO_ZONE).getDayOfWeek();
    if (appProperties.disableOnDays().contains(today)) {
      log.warn("App disabled by configuration on days: {}", appProperties.disableOnDays());

      log.info("\n\n{}\n\n", Files.readAllLines(Paths.get("batch-job-output.txt")).stream().collect(joining("\n")));

      log.warn("App disabled by configuration on days: {}", appProperties.disableOnDays());
      log.info("[DONE]");
      System.exit(0);
    }
  }

  private void candles() {
    if (v20Properties.candlestick().enabled()) {
      candlestickService.runGetNextCandlesBatch();
    }
  }

}
