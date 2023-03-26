package com.markets.getcandleshistoricalbatch;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class OnAppReadyManager {

  private final V20Properties v20Properties;
  private final CandlestickService candlestickService;

  @EventListener
  public void onAppReady(ApplicationReadyEvent ignored) {
    var message = "OnAppReadyManager";
    log.info("[LAUNCHING] {}", message);

    candles();

    log.info("[DONE] {}", message);
    System.exit(0);
  }

  private void candles() {
    if (v20Properties.candlestick().enabled()) {
      candlestickService.runGetNextCandlesBatch();
    }
  }

}
