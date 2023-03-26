package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.model;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument;
import com.oanda.v20.instrument.CandlestickGranularity;
import lombok.Builder;
import lombok.With;

import java.time.ZonedDateTime;

@Builder
@With
public record InstrumentCandleRequestInfo(EInstrument instrument,
                                          CandlestickGranularity granularity,
                                          String outputPath,
                                          String lastLine,
                                          ZonedDateTime dateTime) {
}
