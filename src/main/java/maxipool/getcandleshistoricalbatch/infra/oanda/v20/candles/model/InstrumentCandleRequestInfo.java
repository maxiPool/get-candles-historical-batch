package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model;

import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.Instrument;
import lombok.Builder;
import lombok.With;

import java.time.ZonedDateTime;
import java.util.List;

@Builder
@With
public record InstrumentCandleRequestInfo(Instrument instrument,
                                          CandlestickGranularity granularity,
                                          List<String> outputPaths,
                                          String lastLine,
                                          ZonedDateTime dateTime) {
}
