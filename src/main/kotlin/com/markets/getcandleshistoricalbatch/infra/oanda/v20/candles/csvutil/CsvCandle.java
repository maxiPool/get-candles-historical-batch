package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.oanda.v20.primitives.DateTime;
import lombok.*;

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
  private DateTime time;

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

  @JsonIgnore
  public static String getSchemaHeader() {
    return "time,open,high,low,close,volume,isComplete";
  }

}
