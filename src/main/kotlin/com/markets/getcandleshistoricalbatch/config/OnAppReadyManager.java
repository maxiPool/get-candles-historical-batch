package com.markets.getcandleshistoricalbatch.config;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument.INSTRUMENT_LIST;
import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;


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
  }

  private void candles() {
    if (v20Properties.candlestick().enabled()) {
      log.info("Launching get candles historical data batch job");
      var granularityList = List.of(M15, M1);
      var instrumentList = INSTRUMENT_LIST;

      candlestickService.logLastCandleTimesBreakdown(instrumentList, granularityList);
      candlestickService.getCandlesForMany(instrumentList, granularityList);
    }
  }

}
