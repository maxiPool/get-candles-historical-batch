package maxipool.getcandleshistoricalbatch;

import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.build.JarFreshnessCheck;
import maxipool.getcandleshistoricalbatch.email.EmailService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static java.lang.Boolean.TRUE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.time.Instant.ofEpochSecond;
import static java.time.temporal.ChronoUnit.HOURS;

/**
 * One batch run. Returns the process exit code: 0 = success or nothing to do, 1 = failure (alert sent if possible).
 * Every failure is loud: logged, emailed in one message, and a non-zero exit for the cron wrapper.
 */
@Slf4j
public class BatchRunner {

  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILED = 1;
  private static final ZoneId TORONTO_ZONE = ZoneId.of("America/Toronto");
  private static final String LOCK_FILE_NAME = "a_lock_file.txt";

  private final AppProperties appProperties;
  private final V20Properties v20Properties;
  private final CandlestickService candlestickService;
  private final EmailService emailService;
  private final JarFreshnessCheck jarFreshnessCheck;
  private final Clock clock;

  public BatchRunner(AppProperties appProperties, V20Properties v20Properties, CandlestickService candlestickService,
                     EmailService emailService, JarFreshnessCheck jarFreshnessCheck, Clock clock) {
    this.appProperties = appProperties;
    this.v20Properties = v20Properties;
    this.candlestickService = candlestickService;
    this.emailService = emailService;
    this.jarFreshnessCheck = jarFreshnessCheck;
    this.clock = clock;
  }

  public int run() {
    var today = ZonedDateTime.now(clock.withZone(TORONTO_ZONE)).getDayOfWeek();
    if (appProperties.disableOnDays().contains(today)) {
      log.warn("App disabled by configuration on days: {}", appProperties.disableOnDays());
      return EXIT_OK;
    }
    if (!TRUE.equals(v20Properties.candlestick().enabled())) {
      log.info("Candlestick download disabled (infra.oanda.v20.candlestick.enabled)");
      return EXIT_OK;
    }
    try {
      return runChecked();
    } catch (RuntimeException e) {
      log.error("Batch failed with an exception", e);
      return fail(List.of("Unexpected error: %s: %s".formatted(e.getClass().getSimpleName(), e.getMessage())));
    }
  }

  private int runChecked() {
    if (TRUE.equals(appProperties.staleJarCheck())) {
      var stale = jarFreshnessCheck.check(appProperties.repoDir(), appProperties.buildCommit());
      if (stale.isPresent()) {
        return fail(List.of(stale.get()));
      }
    }

    var outputPath = v20Properties.candlestick().outputPath();
    var folderProblem = CandlestickService.checkDataFolder(outputPath);
    if (folderProblem.isPresent()) {
      return fail(List.of(folderProblem.get()));
    }

    // the data folder exists (checked above); the lock file may be created in it, the folder never is
    var lockFile = Paths.get(outputPath, LOCK_FILE_NAME);
    try (var channel = FileChannel.open(lockFile, java.nio.file.StandardOpenOption.CREATE, READ, WRITE);
         var lock = channel.tryLock()) {
      if (lock == null) {
        log.info("Another instance of the script is already running. Exiting gracefully.");
        return EXIT_OK;
      }
      var lastTimestamp = readTimestamp(channel);
      if (lastTimestamp != 0 && HOURS.between(ofEpochSecond(lastTimestamp), clock.instant()) < 1) {
        log.info("The script was run less than an hour ago. Exiting gracefully.");
        return EXIT_OK;
      }

      log.info("Proceeding with script execution...");
      var report = candlestickService.getOandaHistoricalMarketData();
      if (!report.ok()) {
        return fail(report.problems());
      }
      if (appProperties.isFullRun()) {
        writeTimestamp(channel, clock.instant().getEpochSecond());
        writeSuccessMarker(report);
      } else {
        log.info("Filtered or dry run: lock timestamp and success marker left unchanged");
      }
      log.info("Success: {} new candles across {} pairs", report.newCandles(), report.pairs());
      return EXIT_OK;
    } catch (IOException e) {
      return fail(List.of("Error handling the lock file %s: %s".formatted(lockFile, e.getMessage())));
    }
  }

  private int fail(List<String> problems) {
    var msg = new ArrayList<String>();
    msg.add("As of %s the candles batch FAILED:".formatted(ZonedDateTime.now(clock.withZone(TORONTO_ZONE))));
    problems.forEach(p -> msg.add("- " + p));
    var text = String.join("\n", msg);
    log.error(text);
    try {
      emailService.sendEmail(text);
    } catch (RuntimeException e) {
      log.error("Could not send the failure email (the cron wrapper will alert on the exit code)", e);
    }
    return EXIT_FAILED;
  }

  private void writeSuccessMarker(CandlestickService.RunReport report) {
    var path = appProperties.successMarkerPath();
    if (path == null || path.isBlank()) {
      return;
    }
    var now = clock.instant();
    var content = "%d %s new_candles=%d pairs=%d%n".formatted(now.getEpochSecond(), now, report.newCandles(), report.pairs());
    try {
      var p = Path.of(path);
      var tmp = p.resolveSibling(p.getFileName() + ".tmp");
      Files.writeString(tmp, content);
      Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      // the freshness watchdog will notice the missing update
      log.error("Failed to write success marker {}", path, e);
    }
  }

  private static long readTimestamp(FileChannel channel) {
    try {
      channel.position(0);
      var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
      var line = reader.readLine();
      return (line != null && !line.isBlank()) ? Long.parseLong(line.strip()) : 0;
    } catch (IOException | NumberFormatException e) {
      log.warn("Failed to read timestamp from lock file. Assuming no recent run.", e);
      return 0;
    }
  }

  private static void writeTimestamp(FileChannel channel, long timestamp) throws IOException {
    channel.position(0);
    channel.truncate(0);
    var writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)));
    writer.write(Long.toString(timestamp));
    writer.flush();
  }
}
