package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.resource;

import com.oanda.v20.instrument.CandlestickGranularity;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.config.OandaRestFeignConfig;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model.GetInstrumentListResponse;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "oandaFeignClient",
    url = "${infra.oanda.v20.prodRestUrl}",
    configuration = OandaRestFeignConfig.class
)
public interface OandaRestResource {

  String GRANULARITY = "granularity";
  String FROM = "from";
  String TO = "to";
  String COUNT = "count";

  @GetMapping("/v3/instruments/{instrument}/candles")
  GetCandlesResponse getCandlesFromTo(@PathVariable("instrument") String instrument,
                                      @RequestParam(GRANULARITY) CandlestickGranularity granularity,
                                      @RequestParam(FROM) String from,
                                      @RequestParam(TO) String to);

  @GetMapping("/v3/instruments/{instrument}/candles")
  GetCandlesResponse getCandlesWithCount(@PathVariable("instrument") String instrument,
                                         @RequestParam(GRANULARITY) CandlestickGranularity granularity,
                                         @RequestParam(COUNT) int count);

  /**
   * Get the list of tradeable instruments for the given account.
   */
  @GetMapping("/v3/accounts/{accountId}/instruments")
  GetInstrumentListResponse getInstruments(@PathVariable("accountId") String accountId);

}
