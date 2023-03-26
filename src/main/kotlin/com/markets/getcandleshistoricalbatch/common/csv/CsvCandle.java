package com.markets.getcandleshistoricalbatch.common.csv;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.*;

import java.time.ZonedDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({
    "time",
    "open",
    "high",
    "low",
    "close",
    "volume",
    "isComplete"
})
public class CsvCandle {

  @JsonProperty("time")
  private ZonedDateTime time;

  @JsonProperty("open")
  private Double open;

  @JsonProperty("high")
  private Double high;

  @JsonProperty("low")
  private Double low;

  @JsonProperty("close")
  private Double close;

  @JsonProperty("volume")
  private Long volume;

  @JsonProperty("isComplete")
  private Integer isComplete;

}
