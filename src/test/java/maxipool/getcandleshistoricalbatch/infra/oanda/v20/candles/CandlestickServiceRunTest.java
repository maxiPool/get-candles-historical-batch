package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles;

import com.oanda.v20.instrument.Candlestick;
import com.oanda.v20.instrument.CandlestickData;
import com.oanda.v20.instrument.CandlestickGranularity;
import com.oanda.v20.primitives.Instrument;
import maxipool.getcandleshistoricalbatch.AppProperties;
import maxipool.getcandleshistoricalbatch.common.time.TradingTime;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService.RunReport;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.resource.OandaRestResource;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.GetCandlesResponse;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.CandlestickProperties;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Whole-run behaviour of {@link CandlestickService} against a temp data folder and a stubbed Oanda resource.
 */
class CandlestickServiceRunTest {

  /** Monday 2026-11-02 17:00 Toronto (EST). */
  private static final Instant NOW = Instant.parse("2026-11-02T22:00:00Z");
  private static final String HEADER = "time,open,high,low,close,volume,isComplete";

  @TempDir
  Path root;
  Path out;
  Path copy;

  private final InstrumentsService instruments = mock(InstrumentsService.class);
  private final OandaRestResource oanda = mock(OandaRestResource.class);
  private final List<String[]> requests = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    out = Files.createDirectories(root.resolve("candles-monthly"));
    copy = root.resolve("copy");
    when(instruments.findAll()).thenReturn(List.of(instrument("EUR_USD")));
  }

  // ---------- helpers ----------

  private static Instrument instrument(String name) {
    var i = new Instrument();
    i.setName(name);
    return i;
  }

  private CandlestickService service(AppProperties app) {
    var v20 = new V20Properties(null, null, null, null, null, null, false,
        new CandlestickProperties(true, out.toString(), copy.toString()));
    return new CandlestickService(instruments, oanda, new CandlestickMapperImpl(), v20, app,
        Clock.fixed(NOW, ZoneOffset.UTC), new ChunkedCandleFetcher(oanda, ms -> { }, 0));
  }

  private static AppProperties app() {
    return new AppProperties(null, null, null, false, null, null, null, null, null);
  }

  private static String line(Instant t) {
    return "%s,1.1,1.2,1.0,1.15,10,1".formatted(t.toString());
  }

  private Path file(String instrument, CandlestickGranularity g, String name, Instant... times) throws IOException {
    var dir = Files.createDirectories(out.resolve(instrument).resolve(g.toString()));
    var sb = new StringBuilder(HEADER).append('\n');
    for (var t : times) {
      sb.append(line(t)).append('\n');
    }
    return Files.writeString(dir.resolve(name), sb.toString());
  }

  private static Candlestick candle(Instant t, boolean complete) {
    return new Candlestick()
        .setTime(t.toString())
        .setComplete(complete)
        .setVolume(10L)
        .setMid(new CandlestickData().setO("1.1").setH("1.2").setL("1.0").setC("1.15"));
  }

  private static boolean marketOpen(Instant t) {
    return TradingTime.tradingDurationBetween(t, t.plusSeconds(1)).toMillis() > 0;
  }

  /** Oanda stub: one complete candle per granule in [from, to] (inclusive end: overlaps the next chunk), weekends empty. */
  private void stubMarket(Instant dataEndsAt) {
    when(oanda.getCandlesFromTo(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> {
      String instrument = inv.getArgument(0);
      CandlestickGranularity g = inv.getArgument(1);
      String from = inv.getArgument(2);
      String to = inv.getArgument(3);
      requests.add(new String[]{instrument, g.toString(), from, to});
      var step = CandlestickService.granularityToSeconds(g);
      var candles = new ArrayList<Candlestick>();
      for (var t = Instant.parse(from); !t.isAfter(Instant.parse(to)) && t.isBefore(dataEndsAt); t = t.plusSeconds(step)) {
        if (marketOpen(t)) {
          candles.add(candle(t, true));
        }
      }
      return GetCandlesResponse.builder().candles(candles).granularity(g).instrument(instrument).build();
    });
  }

  private static List<String> dataLines(Path p) throws IOException {
    var lines = Files.readAllLines(p);
    assertEquals(HEADER, lines.getFirst());
    return lines.subList(1, lines.size()).stream().filter(l -> !l.isBlank()).toList();
  }

  // ---------- failure cases ----------

  @Test
  void zeroMatchedFilesFailsLoudlyAndDownloadsNothing() throws IOException {
    // the June-jar situation: files exist but none match what the finder looks for
    file("EUR_USD", M1, "EUR_USD.M1.2026-08.csv", Instant.parse("2026-08-12T20:59:00Z"));
    file("EUR_USD", M15, "notes.csv");

    var report = service(app()).getOandaHistoricalMarketData();

    assertFalse(report.ok());
    assertTrue(report.problems().getFirst().startsWith("0 matched files"), report.problems().toString());
    verify(oanda, never()).getCandlesFromTo(any(), any(), any(), any());
    verify(oanda, never()).getCandlesWithCount(any(), any(), anyInt());
  }

  @Test
  void tooManyPairsWithoutFileFailsInsteadOfReseeding() throws IOException {
    when(instruments.findAll()).thenReturn(List.of(instrument("EUR_USD"), instrument("GBP_USD"), instrument("USD_JPY")));
    file("EUR_USD", M1, "EUR_USD-M1-2026_11.csv", NOW.minusSeconds(600));
    var app = new AppProperties(null, null, null, false, null, 2, null, null, null);

    var report = service(app).getOandaHistoricalMarketData();

    assertFalse(report.ok());
    assertTrue(report.problems().getFirst().contains("5 of 6 pairs have no monthly file"), report.problems().toString());
    verify(oanda, never()).getCandlesWithCount(any(), any(), anyInt());
  }

  @Test
  void failedDownloadFailsThePairRetriesAndNeverFallsBackToCount() throws IOException {
    var last = Instant.parse("2026-11-02T12:00:00Z");
    var m1 = file("EUR_USD", M1, "EUR_USD-M1-2026_11.csv", last);
    var m15 = file("EUR_USD", M15, "EUR_USD-M15-2026_11.csv", last);
    var before = Files.readString(m1);
    when(oanda.getCandlesFromTo(anyString(), any(), anyString(), anyString())).thenThrow(new RuntimeException("HTTP 503"));

    var report = service(app()).getOandaHistoricalMarketData();

    assertFalse(report.ok());
    var problem = report.problems().getFirst();
    assertTrue(problem.startsWith("Failed download/write for 2 pairs") && problem.contains("HTTP 503"), problem);
    assertEquals(before, Files.readString(m1), "nothing written on failure");
    assertEquals(2, Files.readAllLines(m15).size());
    verify(oanda, times(2 * ChunkedCandleFetcher.MAX_ATTEMPTS)).getCandlesFromTo(any(), any(), any(), any());
    verify(oanda, never()).getCandlesWithCount(any(), any(), anyInt());
  }

  @Test
  void transientFailureIsRetried() throws IOException {
    var last = Instant.parse("2026-11-02T12:00:00Z");
    file("EUR_USD", M1, "EUR_USD-M1-2026_11.csv", last);
    file("EUR_USD", M15, "EUR_USD-M15-2026_11.csv", last);
    stubMarket(NOW);
    var calls = new AtomicInteger();
    var real = oanda;
    var flaky = mock(OandaRestResource.class);
    when(flaky.getCandlesFromTo(anyString(), any(), anyString(), anyString())).thenAnswer(inv -> {
      if (calls.getAndIncrement() < 2) {
        throw new RuntimeException("HTTP 429");
      }
      return real.getCandlesFromTo(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
    });
    var v20 = new V20Properties(null, null, null, null, null, null, false,
        new CandlestickProperties(true, out.toString(), copy.toString()));
    var svc = new CandlestickService(instruments, flaky, new CandlestickMapperImpl(), v20, app(),
        Clock.fixed(NOW, ZoneOffset.UTC), new ChunkedCandleFetcher(flaky, ms -> { }, 0));

    var report = svc.getOandaHistoricalMarketData();

    assertTrue(report.ok(), report.problems().toString());
    assertTrue(report.newCandles() > 0);
  }

  @Test
  void zeroNewCandlesOnAWeekdayFails() throws IOException {
    var last = Instant.parse("2026-11-02T12:00:00Z");
    file("EUR_USD", M1, "EUR_USD-M1-2026_11.csv", last);
    file("EUR_USD", M15, "EUR_USD-M15-2026_11.csv", last);
    stubMarket(last.plusSeconds(1)); // Oanda has nothing newer than what is on disk

    var report = service(app()).getOandaHistoricalMarketData();

    assertFalse(report.ok());
    assertTrue(report.problems().stream().anyMatch(p -> p.startsWith("0 new candles on a weekday (MONDAY)")), report.problems().toString());
  }

  @Test
  void staleDataAfterTheRunFails() throws IOException {
    var last = Instant.parse("2026-10-29T12:00:00Z");
    file("EUR_USD", M1, "EUR_USD-M1-2026_10.csv", last);
    file("EUR_USD", M15, "EUR_USD-M15-2026_10.csv", last);
    stubMarket(Instant.parse("2026-10-29T22:00:00Z")); // Oanda data stops Thursday: 47 market hours before NOW

    var report = service(app()).getOandaHistoricalMarketData();

    assertFalse(report.ok());
    var stale = report.problems().stream().filter(p -> p.startsWith("Stale data")).findFirst().orElseThrow();
    assertTrue(stale.contains("EUR_USD-M1 last=2026-10-29T21:59:00Z") && stale.contains("for 2 pairs"), stale);
  }

  @Test
  void unknownOnlyInstrumentFails() {
    var app = new AppProperties(null, null, null, false, null, null, Set.of("NOPE_USD"), null, null);
    var report = service(app).getOandaHistoricalMarketData();
    assertFalse(report.ok());
    assertTrue(report.problems().getFirst().contains("NOPE_USD"));
  }

  // ---------- backfill ----------

  @Test
  void eightyDayGapIsBackfilledInChunksAcrossMonthsWithoutDuplicates() throws IOException {
    // master state: last candle 2026-08-12T20:59Z (M1) / 20:45Z (M15); legacy underscore name for M15
    var m1Aug = file("EUR_USD", M1, "EUR_USD-M1-2026_08.csv",
        Instant.parse("2026-08-12T20:58:00Z"), Instant.parse("2026-08-12T20:59:00Z"));
    var m15Aug = file("EUR_USD", M15, "EUR_USD_M15_2026_08.csv", Instant.parse("2026-08-12T20:45:00Z"));
    stubMarket(NOW);

    RunReport report = service(app()).getOandaHistoricalMarketData();

    assertTrue(report.ok(), report.problems().toString());

    // every request stays under Oanda's cap, and the chunks tile [from, to) without holes
    for (var g : List.of(M1, M15)) {
      var rs = requests.stream().filter(r -> r[1].equals(g.toString())).toList();
      var maxSpan = Duration.ofSeconds(ChunkedCandleFetcher.GRANULES_PER_CHUNK * CandlestickService.granularityToSeconds(g));
      for (int i = 0; i < rs.size(); i++) {
        var from = Instant.parse(rs.get(i)[2]);
        var to = Instant.parse(rs.get(i)[3]);
        assertFalse(Duration.between(from, to).compareTo(maxSpan) > 0, "chunk too large: " + String.join(" ", rs.get(i)));
        if (i > 0) {
          assertEquals(rs.get(i - 1)[3], rs.get(i)[2], "chunks must be contiguous");
        }
      }
      assertEquals(NOW.minusSeconds(10).toString(), rs.getLast()[3]);
    }
    assertTrue(requests.stream().filter(r -> r[1].equals("M1")).count() >= 26, "M1 needs ~27 chunks for 82 days");

    // M1: appended to August, new files for Sep/Oct/Nov, contiguous minutes (except weekends), no duplicates
    var m1Dir = m1Aug.getParent();
    var all = new ArrayList<String>();
    for (var name : List.of("EUR_USD-M1-2026_08.csv", "EUR_USD-M1-2026_09.csv", "EUR_USD-M1-2026_10.csv", "EUR_USD-M1-2026_11.csv")) {
      assertTrue(Files.exists(m1Dir.resolve(name)), name);
      all.addAll(dataLines(m1Dir.resolve(name)));
    }
    var times = all.stream().map(l -> Instant.parse(l.split(",")[0])).toList();
    assertEquals(times.size(), new HashSet<>(times).size(), "no duplicate candles");
    for (int i = 1; i < times.size(); i++) {
      var prev = times.get(i - 1);
      var cur = times.get(i);
      assertTrue(cur.isAfter(prev));
      if (!cur.equals(prev.plusSeconds(60))) {
        assertEquals(Duration.ZERO, TradingTime.tradingDurationBetween(prev.plusSeconds(60), cur), "hole between " + prev + " and " + cur);
      }
    }
    assertEquals(Instant.parse("2026-08-12T20:58:00Z"), times.getFirst());
    assertEquals(Instant.parse("2026-11-02T21:59:00Z"), times.getLast());
    // new candles = everything beyond the 2 M1 lines and 1 M15 line that were on disk
    assertEquals(times.size() - 2 + dataLinesCount(m15Aug.getParent()) - 1, report.newCandles());

    // M15: legacy-named August file kept and appended to; later months use the canonical name
    var m15 = dataLines(m15Aug);
    assertEquals("2026-08-31T23:45:00Z", m15.getLast().split(",")[0]);
    assertTrue(Files.exists(m15Aug.getParent().resolve("EUR_USD-M15-2026_09.csv")));

    // second disk copy mirrors every written file
    assertEquals(Files.readString(m1Dir.resolve("EUR_USD-M1-2026_10.csv")),
        Files.readString(copy.resolve("EUR_USD/M1/EUR_USD-M1-2026_10.csv")));
  }

  private static long dataLinesCount(Path dir) throws IOException {
    try (var files = Files.list(dir)) {
      long n = 0;
      for (var f : files.toList()) {
        n += dataLines(f).size();
      }
      return n;
    }
  }

  @Test
  void rerunAddsNothingAndKeepsFilesIdentical() throws IOException {
    var last = Instant.parse("2026-11-02T12:00:00Z");
    var m1 = file("EUR_USD", M1, "EUR_USD-M1-2026_11.csv", last);
    file("EUR_USD", M15, "EUR_USD-M15-2026_11.csv", last);
    stubMarket(NOW);
    assertTrue(service(app()).getOandaHistoricalMarketData().ok());
    var afterFirst = Files.readString(m1);

    // a second run one hour later (Oanda has nothing newer) must not duplicate
    var v20 = new V20Properties(null, null, null, null, null, null, false,
        new CandlestickProperties(true, out.toString(), copy.toString()));
    var later = new CandlestickService(instruments, oanda, new CandlestickMapperImpl(), v20, app(),
        Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC), new ChunkedCandleFetcher(oanda, ms -> { }, 0));
    later.getOandaHistoricalMarketData();

    assertEquals(afterFirst, Files.readString(m1));
  }

  @Test
  void incompleteCandlesAreNotWritten() throws IOException {
    var last = Instant.parse("2026-11-02T20:00:00Z");
    var m1 = file("EUR_USD", M1, "EUR_USD-M1-2026_11.csv", last);
    file("EUR_USD", M15, "EUR_USD-M15-2026_11.csv", last);
    when(oanda.getCandlesFromTo(anyString(), any(), anyString(), anyString())).thenReturn(GetCandlesResponse.builder()
        .candle(candle(Instant.parse("2026-11-02T20:01:00Z"), true))
        .candle(candle(Instant.parse("2026-11-02T20:02:00Z"), false))
        .build());

    service(app()).getOandaHistoricalMarketData();

    var lines = dataLines(m1);
    assertEquals(2, lines.size());
    assertTrue(lines.getLast().startsWith("2026-11-02T20:01:00Z"));
  }

  @Test
  void dryRunWritesNothing() throws IOException {
    var m1 = file("EUR_USD", M1, "EUR_USD-M1-2026_08.csv", Instant.parse("2026-08-12T20:59:00Z"));
    var m15 = file("EUR_USD", M15, "EUR_USD-M15-2026_08.csv", Instant.parse("2026-08-12T20:45:00Z"));
    var before = Files.readString(m1);
    stubMarket(NOW);
    var app = new AppProperties(null, null, null, false, null, null, Set.of("EUR_USD"), true, null);

    var report = service(app).getOandaHistoricalMarketData();

    assertTrue(report.ok(), report.problems().toString());
    assertTrue(report.newCandles() > 50_000);
    assertEquals(before, Files.readString(m1));
    try (var files = Files.list(m15.getParent())) {
      assertEquals(1, files.count());
    }
    assertFalse(Files.exists(copy));
  }
}
