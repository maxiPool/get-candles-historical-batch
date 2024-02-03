package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.oanda.v20.primitives.Instrument;
import lombok.Builder;
import lombok.With;

import java.util.List;

@Builder
@With
public record GetInstrumentListResponse(@JsonProperty("instruments") List<Instrument> instruments) {
}
