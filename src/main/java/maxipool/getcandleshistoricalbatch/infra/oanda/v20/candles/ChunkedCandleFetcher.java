package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickGranularity;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.Rfc3339.YMDHMS_FORMATTER;

/**
 * Downloads [from, to) in chunks small enough for Oanda's 5000-candle cap, with retry/backoff and a global
 * request throttle. Any chunk that still fails after the retries fails the whole range: no silent fallback.
 */
@Slf4j
public class ChunkedCandleFetcher {

  /** Granules per request; margin under Oanda's 5000 cap. */
  static final int GRANULES_PER_CHUNK = 4_500;
  static final int MAX_ATTEMPTS = 4;
  private static final long BASE_BACKOFF_MS = 1_000;

  @FunctionalInterface
  public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final OandaRestResource resource;
  private final Sleeper sleeper;
  private final long minIntervalMs;
  private final Object throttleLock = new Object();
  private long nextSlotMs = 0;

  public ChunkedCandleFetcher(OandaRestResource resource, Sleeper sleeper, long minIntervalMs) {
    this.resource = resource;
    this.sleeper = sleeper;
    this.minIntervalMs = minIntervalMs;
  }

  public static class DownloadException extends RuntimeException {
    public DownloadException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public List<Candlestick> fetch(String instrument, CandlestickGranularity granularity, Instant from, Instant to) {
    var span = Duration.ofSeconds(GRANULES_PER_CHUNK * CandlestickService.granularityToSeconds(granularity));
    var all = new ArrayList<Candlestick>();
    var cursor = from;
    while (cursor.isBefore(to)) {
      var end = cursor.plus(span);
      if (end.isAfter(to)) {
        end = to;
      }
      var response = fetchChunk(instrument, granularity, cursor, end);
      if (response != null && response.getCandles() != null) {
        all.addAll(response.getCandles());
      }
      cursor = end;
    }
    return all;
  }

  private GetCandlesResponse fetchChunk(String instrument, CandlestickGranularity granularity, Instant from, Instant to) {
    var fromStr = YMDHMS_FORMATTER.format(from);
    var toStr = YMDHMS_FORMATTER.format(to);
    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        throttle();
        return resource.getCandlesFromTo(instrument, granularity, fromStr, toStr);
      } catch (RuntimeException e) {
        last = e;
        log.warn("Attempt {}/{} failed for {}-{} [{}, {}): {}", attempt, MAX_ATTEMPTS, instrument, granularity,
            fromStr, toStr, e.getMessage());
        if (attempt < MAX_ATTEMPTS) {
          sleepQuietly(BASE_BACKOFF_MS << (attempt - 1));
        }
      }
    }
    throw new DownloadException("download failed for %s-%s [%s, %s) after %d attempts: %s"
        .formatted(instrument, granularity, fromStr, toStr, MAX_ATTEMPTS, last.getMessage()), last);
  }

  private void throttle() {
    long waitMs;
    synchronized (throttleLock) {
      var now = System.currentTimeMillis();
      var slot = Math.max(now, nextSlotMs);
      nextSlotMs = slot + minIntervalMs;
      waitMs = slot - now;
    }
    if (waitMs > 0) {
      sleepQuietly(waitMs);
    }
  }

  private void sleepQuietly(long ms) {
    try {
      sleeper.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DownloadException("interrupted", e);
    }
  }
}
