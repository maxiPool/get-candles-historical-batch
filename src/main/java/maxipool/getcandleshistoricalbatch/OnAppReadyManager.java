package maxipool.getcandleshistoricalbatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.build.JarFreshnessCheck;
import maxipool.getcandleshistoricalbatch.email.EmailService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@RequiredArgsConstructor
@Slf4j
public class OnAppReadyManager {

  private final AppProperties appProperties;
  private final V20Properties v20Properties;
  private final CandlestickService candlestickService;
  private final EmailService emailService;

  @EventListener
  public void onAppReady(ApplicationReadyEvent ignored) {
    log.info("[APP READY] build commit {}", appProperties.buildCommit());
    var code = new BatchRunner(appProperties, v20Properties, candlestickService, emailService,
        new JarFreshnessCheck(JarFreshnessCheck.processGit()), Clock.systemUTC()).run();
    log.info("[DONE] exit code {}", code);
    System.exit(code);
  }
}
