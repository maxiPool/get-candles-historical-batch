package maxipool.getcandleshistoricalbatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

import static java.lang.Boolean.TRUE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.time.Instant.now;
import static java.time.Instant.ofEpochSecond;
import static java.time.temporal.ChronoUnit.HOURS;


@Component
@RequiredArgsConstructor
@Slf4j
public class OnAppReadyManager {

  private static final ZoneId TORONTO_ZONE = ZoneId.of("America/Toronto");

  private final AppProperties appProperties;
  private final V20Properties v20Properties;
  private final CandlestickService candlestickService;

  @EventListener
  public void onAppReady(ApplicationReadyEvent ignored) {
    log.info("[APP READY]");
    exitIfDisabledDays();

    candles();

    log.info("[DONE]");
    System.exit(0);
  }

  private void exitIfDisabledDays() {
    var today = ZonedDateTime.now(TORONTO_ZONE).getDayOfWeek();
    if (appProperties.disableOnDays().contains(today)) {
      log.warn("App disabled by configuration on days: {}", appProperties.disableOnDays());
      log.info("[DONE]");
      System.exit(0);
    }
  }

  private void candles() {
    if (TRUE.equals(v20Properties.candlestick().enabled())) {
      preventDuplicateRun(candlestickService::getOandaHistoricalMarketData);
    }
  }

  @Value("${app.lock-file-path}")
  private String lockFilePath;

  private void preventDuplicateRun(Supplier<Boolean> resultSupplier) {
    var lockFile = new File(lockFilePath);
    var parentDir = lockFile.getParentFile();
    if (parentDir != null && !parentDir.exists()) {
      parentDir.mkdirs();
    }

    // Create lock file only if it doesn't exist (preserves existing timestamp data)
    if (!lockFile.exists()) {
      try {
        lockFile.createNewFile();
        log.info("Created new lock file: {}", lockFile);
      } catch (IOException e) {
        log.error("Failed to create lock file: {}", lockFile, e);
        return;
      }
    }

    try (var channel = FileChannel.open(lockFile.toPath(), READ, WRITE);
         var lock = channel.tryLock()) {
      log.info("Trying to acquire lock file: {}", lockFile);

      if (lock != null) {
        log.info("Lock File acquired, checking last run timestamp...");

        var lastTimestamp = readTimestampFromFile(channel);
        var currentTime = now().getEpochSecond();

        if (lastTimestamp != 0 && HOURS.between(ofEpochSecond(lastTimestamp), now()) < 1) {
          log.info("The script was run less than an hour ago. Exiting gracefully.");
          return;
        }

        log.info("Proceeding with script execution...");
        var result = resultSupplier.get();

        if (TRUE.equals(result)) {
          writeTimestampToFile(channel, currentTime);
        }
        log.info("Script execution completed and timestamp updated.");
      } else {
        log.info("Another instance of the script is already running. Exiting gracefully.");
      }
    } catch (IOException e) {
      log.error("Error handling the lock file.", e);
    }
  }

  private static long readTimestampFromFile(FileChannel channel) {
    try {
      channel.position(0);
      var reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
      var line = reader.readLine();
      return (line != null && !line.isBlank()) ? Long.parseLong(line) : 0;
    } catch (IOException | NumberFormatException e) {
      log.warn("Failed to read timestamp from file. Assuming no recent run.", e);
      return 0;
    }
  }

  private static void writeTimestampToFile(FileChannel channel, long timestamp) {
    try {
      channel.position(0);
      channel.truncate(0); // Clear the file before writing
      var writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)));
      writer.write(Long.toString(timestamp));
      writer.flush();
    } catch (IOException e) {
      log.error("Failed to write timestamp to file.", e);
    }
  }

}
