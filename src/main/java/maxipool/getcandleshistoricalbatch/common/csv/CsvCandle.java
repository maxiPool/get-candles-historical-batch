package maxipool.getcandleshistoricalbatch.common.csv;

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
@EqualsAndHashCode(of = "time")
public class CsvCandle implements Comparable<CsvCandle> {

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

  @Override
  public int compareTo(CsvCandle o) {
    return time.compareTo(o.getTime());
  }
}
