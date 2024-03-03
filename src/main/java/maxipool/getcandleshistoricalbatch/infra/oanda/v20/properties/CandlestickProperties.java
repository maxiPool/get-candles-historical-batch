package maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties;

import lombok.Builder;

import java.util.List;

@Builder
public record CandlestickProperties(Boolean enabled,
                                    List<String> outputPathTemplates) {
}
