package maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties;

import lombok.Builder;

@Builder
public record CandlestickProperties(Boolean enabled,
                                    String outputPath,
                                    String outputPathTemplate,
                                    String copyOutputPath,
                                    String fileTemplateMatcher) {
}
