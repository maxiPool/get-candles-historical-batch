package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.resource;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.config.OandaRestFeignConfig;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument;
import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import com.oanda.v20.instrument.CandlestickGranularity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "oandaFeignClient",
    url = "${infra.oanda.v20.devRestUrl}",
    configuration = OandaRestFeignConfig.class
)
public interface OandaRestResource {

  String GRANULARITY = "granularity";
  String FROM = "from";
  String TO = "to";
  String COUNT = "count";

  @GetMapping("/v3/instruments/{instrument}/candles")
  GetCandlesResponse getCandlesFromTo(@PathVariable("instrument") EInstrument instrument,
                                      @RequestParam(GRANULARITY) CandlestickGranularity granularity,
                                      @RequestParam(FROM) String from,
                                      @RequestParam(TO) String to);

  @GetMapping("/v3/instruments/{instrument}/candles")
  GetCandlesResponse getCandlesWithCount(@PathVariable("instrument") EInstrument instrument,
                                         @RequestParam(GRANULARITY) CandlestickGranularity granularity,
                                         @RequestParam(COUNT) int count);

}
