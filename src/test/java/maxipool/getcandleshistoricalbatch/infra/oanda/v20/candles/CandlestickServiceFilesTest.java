package maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles;

import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService.IG;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.oanda.v20.instrument.CandlestickGranularity.M1;
import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static org.junit.jupiter.api.Assertions.*;

class CandlestickServiceFilesTest {

  @TempDir
  Path out;

  private Path touch(String instrument, String gran, String name) throws IOException {
    var dir = Files.createDirectories(out.resolve(instrument).resolve(gran));
    return Files.writeString(dir.resolve(name), "header\n");
  }

  @Test
  void underscoreOnlyFolderIsFoundAndLatestMonthWins() throws IOException {
    touch("AUD_CAD", "M15", "AUD_CAD_M15_2026_07.csv");
    var latest = touch("AUD_CAD", "M15", "AUD_CAD_M15_2026_08.csv");
    var ig = new IG("AUD_CAD", M15);

    var result = CandlestickService.findLatestFiles(out.toString(), List.of(ig));

    assertEquals(1, result.size());
    assertEquals(latest, result.get(ig).orElseThrow());
  }

  @Test
  void emptyOrMissingFolderStillYieldsOneEntryPerPair() throws IOException {
    Files.createDirectories(out.resolve("EUR_USD").resolve("M15")); // exists, no files
    touch("EUR_USD", "M1", "unrelated.csv");                       // exists, nothing matching
    var igs = List.of(new IG("EUR_USD", M15), new IG("EUR_USD", M1), new IG("GBP_USD", M15));

    var result = CandlestickService.findLatestFiles(out.toString(), igs);

    assertEquals(3, result.size());
    assertTrue(result.values().stream().allMatch(java.util.Optional::isEmpty));
  }

  @Test
  void mixedFolderPicksLatestMonthAndPrefersHyphenOnTie() throws IOException {
    touch("AUD_CAD", "M15", "AUD_CAD_M15_2026_07.csv");
    touch("AUD_CAD", "M15", "AUD_CAD_M15_2026_08.csv");
    var hyphen = touch("AUD_CAD", "M15", "AUD_CAD-M15-2026_08.csv");
    var ig = new IG("AUD_CAD", M15);

    assertEquals(hyphen, CandlestickService.findLatestFiles(out.toString(), List.of(ig)).get(ig).orElseThrow());

    var newer = touch("AUD_CAD", "M15", "AUD_CAD_M15_2026_09.csv");
    assertEquals(newer, CandlestickService.findLatestFiles(out.toString(), List.of(ig)).get(ig).orElseThrow());
  }

  @Test
  void matcherDoesNotConfuseGranularities() {
    assertTrue(CandlestickService.isMatchingFile(Path.of("A_B-M1-2026_01.csv"), "A_B", "M1"));
    assertTrue(CandlestickService.isMatchingFile(Path.of("A_B_M1_2026_01.csv"), "A_B", "M1"));
    assertFalse(CandlestickService.isMatchingFile(Path.of("A_B_M15_2026_01.csv"), "A_B", "M1"));
  }

  @Test
  void dataFolderMissingEmptyOrPresent() throws IOException {
    assertTrue(CandlestickService.checkDataFolder(out.resolve("nope").toString()).orElseThrow().contains("missing"));
    assertTrue(CandlestickService.checkDataFolder(out.toString()).orElseThrow().contains("empty"));
    Files.writeString(out.resolve("a_lock_file.txt"), "1");
    assertTrue(CandlestickService.checkDataFolder(out.toString()).orElseThrow().contains("empty"));
    touch("EUR_USD", "M1", "EUR_USD-M1-2026_08.csv");
    assertTrue(CandlestickService.checkDataFolder(out.toString()).isEmpty());
    assertFalse(Files.exists(out.resolve("nope")), "the check never creates the folder");
  }
}
