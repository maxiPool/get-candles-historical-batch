package com.markets.getcandleshistoricalbatch.infra.oanda.v20.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import lombok.*;

import java.util.List;

@Builder
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor
public class GetCandlesResponse {

  @Singular
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private List<Candlestick> candles;
  private CandlestickGranularity granularity;
  private String instrument;

}
