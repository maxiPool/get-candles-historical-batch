package maxipool.getcandleshistoricalbatch.common.time;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static maxipool.getcandleshistoricalbatch.common.time.TradingTime.tradingDurationBetween;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingTimeTest {

  private static Instant t(String s) {
    return Instant.parse(s);
  }

  @Test
  void weekdayIntervalCountsFully() {
    assertEquals(Duration.ofHours(5), tradingDurationBetween(t("2026-10-28T10:00:00Z"), t("2026-10-28T15:00:00Z")));
  }

  @Test
  void weekendClosureIsExcluded() {
    // Fri 2026-10-30 21:00Z = 17:00 EDT close; Sun 2026-11-01 22:00Z = 17:00 EST open (DST ended in between)
    var friClose = t("2026-10-30T21:00:00Z");
    var sunOpen = t("2026-11-01T22:00:00Z");
    assertEquals(Duration.ZERO, tradingDurationBetween(friClose, sunOpen));
    // Friday 20:59Z candle checked Monday 17:00 Toronto (22:00Z): 1 min Friday + 24 h Sun->Mon
    assertEquals(Duration.ofHours(24).plusMinutes(1),
        tradingDurationBetween(t("2026-10-30T20:59:00Z"), t("2026-11-02T22:00:00Z")));
  }

  @Test
  void startingInsideTheWeekend() {
    assertEquals(Duration.ofHours(2), tradingDurationBetween(t("2026-10-31T12:00:00Z"), t("2026-11-02T00:00:00Z")));
  }

  @Test
  void multiWeekGapSubtractsEveryWeekend() {
    // 2026-08-12T20:59Z (Wed) -> 2026-11-02T22:00Z (Mon): 12 weekends (Fri Aug 14 .. Fri Oct 30) of 48 h,
    // the last one 49 h long because DST ended on Sun Nov 1
    var d = tradingDurationBetween(t("2026-08-12T20:59:00Z"), t("2026-11-02T22:00:00Z"));
    var calendar = Duration.between(t("2026-08-12T20:59:00Z"), t("2026-11-02T22:00:00Z"));
    assertEquals(calendar.minusHours(12 * 48 + 1), d);
  }

  @Test
  void reversedIntervalIsZero() {
    assertEquals(Duration.ZERO, tradingDurationBetween(t("2026-10-28T15:00:00Z"), t("2026-10-28T10:00:00Z")));
  }
}
