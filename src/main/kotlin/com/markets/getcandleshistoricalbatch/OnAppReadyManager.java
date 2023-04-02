package com.markets.getcandleshistoricalbatch;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;


@Component
@RequiredArgsConstructor
@Slf4j
public class OnAppReadyManager {

  private static final ZoneId TORONTO_ZONE = ZoneId.of("America/Toronto");

  private final AppProperties appProperties;
  private final V20Properties v20Properties;
  private final CandlestickService candlestickService;

  @EventListener
  public void onAppReady(ApplicationReadyEvent ignored) {
    log.info("[APP READY]");
    exitIfDisabledDays();

    candles();

    log.info("[DONE]");
    System.exit(0);
  }

  private void exitIfDisabledDays() {
    var today = ZonedDateTime.now(TORONTO_ZONE).getDayOfWeek();
    if (appProperties.disableOnDays().contains(today)) {
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
