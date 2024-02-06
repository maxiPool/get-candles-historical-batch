package maxipool.getcandleshistoricalbatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;


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
    if (v20Properties.candlestick().enabled()) {
      preventDuplicateRun(candlestickService::runGetNextCandlesBatch);
    }
  }

  @Value("${app.lock-file-path}")
  private String lockFilePath;

  private void preventDuplicateRun(Runnable runnable) {
    try (var channel = FileChannel.open(new File(lockFilePath).toPath(), CREATE, WRITE);
         var lock = channel.tryLock()
    ) {
      if (lock != null) {
        log.info("Lock File acquired, script execution started...");
        runnable.run();
        log.info("Script execution completed.");
      } else {
        log.info("Another instance of the script is already running. Exiting gracefully.");
      }
    } catch (IOException e) {
      log.error("", e);
    }
  }

}
