package maxipool.getcandleshistoricalbatch.common.time;

import lombok.experimental.UtilityClass;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * FX market hours: the market is closed from Friday 17:00 to Sunday 17:00 New York time.
 */
@UtilityClass
public class TradingTime {

  public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
  private static final LocalTime CLOSE = LocalTime.of(17, 0);

  /**
   * Time the market was open between {@code from} and {@code to} (zero when {@code to <= from}).
   */
  public static Duration tradingDurationBetween(Instant from, Instant to) {
    if (!to.isAfter(from)) {
      return Duration.ZERO;
    }
    var total = Duration.between(from, to);
    // first weekend window that can overlap [from, to): the one starting on the Friday at or before `from`
    var windowStart = ZonedDateTime.ofInstant(from, NEW_YORK)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
        .with(CLOSE);
    while (windowStart.toInstant().isBefore(to)) {
      var ws = windowStart.toInstant();
      var we = windowStart.plusDays(2).toInstant();
      var overlapStart = ws.isAfter(from) ? ws : from;
      var overlapEnd = we.isBefore(to) ? we : to;
      if (overlapEnd.isAfter(overlapStart)) {
        total = total.minus(Duration.between(overlapStart, overlapEnd));
      }
      windowStart = windowStart.plusWeeks(1);
    }
    return total;
  }
}
