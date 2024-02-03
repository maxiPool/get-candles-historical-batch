package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model;

import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.Instrument;
import lombok.Builder;
import lombok.With;

import java.time.ZonedDateTime;

@Builder
@With
public record InstrumentCandleRequestInfo(Instrument instrument,
                                          CandlestickGranularity granularity,
                                          String outputPath,
                                          String lastLine,
                                          ZonedDateTime dateTime) {
}
