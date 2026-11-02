package maxipool.getcandleshistoricalbatch;

import maxipool.getcandleshistoricalbatch.common.build.JarFreshnessCheck;
import maxipool.getcandleshistoricalbatch.email.EmailService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService.RunReport;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.CandlestickProperties;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static maxipool.getcandleshistoricalbatch.BatchRunner.EXIT_FAILED;
import static maxipool.getcandleshistoricalbatch.BatchRunner.EXIT_OK;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BatchRunnerTest {

  /** Monday 2026-11-02 17:00 Toronto. */
  private static final Instant NOW = Instant.parse("2026-11-02T22:00:00Z");

  @TempDir
  Path root;
  Path out;
  Path marker;

  private final CandlestickService service = mock(CandlestickService.class);
  private final EmailService email = mock(EmailService.class);
  private final JarFreshnessCheck freshJar = new JarFreshnessCheck(args -> new JarFreshnessCheck.Result(0, "sha\n"));

  @BeforeEach
  void setUp() throws IOException {
    out = root.resolve("candles-monthly");
    Files.createDirectories(out.resolve("EUR_USD/M1"));
    marker = root.resolve("last-success.txt");
  }

  private AppProperties app(Set<String> only, boolean staleCheck) {
    return new AppProperties(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), "/repo", "abc123", staleCheck, null, null,
        only, null, marker.toString());
  }

  private BatchRunner runner(AppProperties app, Path outputPath, JarFreshnessCheck jar, Instant now) {
    var v20 = new V20Properties(null, null, null, null, null, null, false,
        new CandlestickProperties(true, outputPath.toString(), root.resolve("copy").toString()));
    return new BatchRunner(app, v20, service, email, jar, Clock.fixed(now, ZoneOffset.UTC));
  }

  private BatchRunner runner() {
    return runner(app(null, true), out, freshJar, NOW);
  }

  private String sentEmail() {
    var captor = ArgumentCaptor.forClass(String.class);
    verify(email).sendEmail(captor.capture());
    return captor.getValue();
  }

  @Test
  void successWritesLockTimestampAndMarker() throws IOException {
    when(service.getOandaHistoricalMarketData()).thenReturn(new RunReport(List.of(), 1234, 246));

    assertEquals(EXIT_OK, runner().run());

    assertEquals(Long.toString(NOW.getEpochSecond()), Files.readString(out.resolve("a_lock_file.txt")));
    assertTrue(Files.readString(marker).startsWith(NOW.getEpochSecond() + " 2026-11-02T22:00:00Z new_candles=1234"));
    verifyNoInteractions(email);
  }

  @Test
  void problemsFromTheRunAreEmailedAndExitNonZero() {
    when(service.getOandaHistoricalMarketData()).thenReturn(new RunReport(List.of("0 matched files: x", "Stale data: y"), 0, 246));

    assertEquals(EXIT_FAILED, runner().run());

    var mail = sentEmail();
    assertTrue(mail.contains("FAILED") && mail.contains("- 0 matched files: x") && mail.contains("- Stale data: y"), mail);
    assertFalse(Files.exists(marker));
  }

  @Test
  void missingDataFolderFailsAndIsNotCreated() {
    var missing = root.resolve("gone/candles-monthly");

    assertEquals(EXIT_FAILED, runner(app(null, true), missing, freshJar, NOW).run());

    assertTrue(sentEmail().contains("Data folder is missing"));
    assertFalse(Files.exists(missing), "the batch must never recreate a missing data folder (12 Aug incident)");
    verifyNoInteractions(service);
  }

  @Test
  void emptyDataFolderFails() throws IOException {
    var empty = Files.createDirectories(root.resolve("empty"));

    assertEquals(EXIT_FAILED, runner(app(null, true), empty, freshJar, NOW).run());

    assertTrue(sentEmail().contains("Data folder is empty"));
    verifyNoInteractions(service);
  }

  @Test
  void staleJarFailsBeforeDownloading() {
    var staleJar = new JarFreshnessCheck(args ->
        new JarFreshnessCheck.Result(args.contains("rev-parse") ? 0 : 1, "headsha\n"));

    assertEquals(EXIT_FAILED, runner(app(null, true), out, staleJar, NOW).run());

    assertTrue(sentEmail().contains("Stale jar: built from abc123"));
    verifyNoInteractions(service);
  }

  @Test
  void staleJarCheckCanBeDisabled() {
    var staleJar = new JarFreshnessCheck(args -> new JarFreshnessCheck.Result(1, ""));
    when(service.getOandaHistoricalMarketData()).thenReturn(new RunReport(List.of(), 1, 2));

    assertEquals(EXIT_OK, runner(app(null, false), out, staleJar, NOW).run());
  }

  @Test
  void emailFailureStillExitsNonZero() {
    when(service.getOandaHistoricalMarketData()).thenReturn(new RunReport(List.of("boom"), 0, 2));
    doThrow(new RuntimeException("smtp down")).when(email).sendEmail(anyString());

    assertEquals(EXIT_FAILED, runner().run());
  }

  @Test
  void unexpectedExceptionIsEmailedAndExitsNonZero() {
    when(service.getOandaHistoricalMarketData()).thenThrow(new IllegalStateException("Failed to get instruments from Oanda: 401"));

    assertEquals(EXIT_FAILED, runner().run());

    assertTrue(sentEmail().contains("Failed to get instruments from Oanda: 401"));
  }

  @Test
  void disabledDayExitsZeroWithoutWork() {
    var saturday = Instant.parse("2026-10-31T21:00:00Z");

    assertEquals(EXIT_OK, runner(app(null, true), out, freshJar, saturday).run());

    verifyNoInteractions(service, email);
  }

  @Test
  void recentRunExitsZeroWithoutWork() throws IOException {
    Files.writeString(out.resolve("a_lock_file.txt"), Long.toString(NOW.minusSeconds(600).getEpochSecond()));

    assertEquals(EXIT_OK, runner().run());

    verifyNoInteractions(service);
  }

  @Test
  void filteredRunLeavesLockTimestampAndMarkerAlone() throws IOException {
    when(service.getOandaHistoricalMarketData()).thenReturn(new RunReport(List.of(), 10, 2));

    assertEquals(EXIT_OK, runner(app(Set.of("EUR_USD"), true), out, freshJar, NOW).run());

    assertEquals("", Files.readString(out.resolve("a_lock_file.txt")));
    assertFalse(Files.exists(marker));
  }

  @Test
  void jarCheckReceivesRepoAndCommit() {
    var seen = new java.util.ArrayList<List<String>>();
    var jar = new JarFreshnessCheck(args -> {
      seen.add(args);
      return new JarFreshnessCheck.Result(0, "sha\n");
    });
    when(service.getOandaHistoricalMarketData()).thenReturn(new RunReport(List.of(), 1, 2));

    runner(app(null, true), out, jar, NOW).run();

    assertTrue(seen.stream().anyMatch(a -> a.containsAll(List.of("/repo", "abc123"))));
    verify(service, times(1)).getOandaHistoricalMarketData();
    verify(email, never()).sendEmail(any());
  }
}
