package maxipool.getcandleshistoricalbatch;

import com.oanda.v20.primitives.Instrument;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.CandlestickProperties;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static com.oanda.v20.instrument.CandlestickGranularity.M15;
import static java.util.Collections.emptyList;
import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.CandlestickService.MAX_CANDLE_COUNT_OANDA_API;
import static maxipool.getcandleshistoricalbatch.infra.oanda.v20.model.EInstrument.USD_CAD;
import static org.mockito.Mockito.when;


@Disabled
@Slf4j
@SpringBootTest
@ActiveProfiles({"local"})
class DemoCandlesticksTest {

  @MockBean
  private OnAppReadyManager onAppReadyManager;

  @SpyBean
  private V20Properties v20Properties;

  @Autowired
  private CandlestickService candlestickService;

  private final Instrument AU200_AUD, AUD_CAD;

  {
    AU200_AUD = new Instrument();
    AUD_CAD = new Instrument();

    AU200_AUD.setName("AU200_AUD");
    AUD_CAD.setName("AUD_CAD");
  }

  @BeforeEach
  void beforeEach() {
    when(v20Properties.candlestick())
        .thenReturn(CandlestickProperties
            .builder()
            .enabled(true)
            .outputPathTemplates(List.of("src\\test\\resources\\outputFiles\\%s-candles-%s.csv"))
            .build());
  }

  /**
   * It seems the from-to timestamp endpoint doesn't work all that great.
   * So let's try to do it using the count endpoint.
   * We will need to discard while the resource candles are <= last candle time to correctly merge them.
   */
  @Test
  void should_getCandlesForAU200_AUD_15M_whenFileExists_andUsingCountEndpoint() {
    var candleRequestInfo =
        candlestickService.getRequestInfoList(List.of(AU200_AUD), List.of(M15)).get(0);
    candlestickService.getCandlesFor(candleRequestInfo);
  }

  @Test
  void should_getCandlesForAU200_AUD_15M_whenFileExists() {
    var soft = new SoftAssertions();
    var candleRequestInfoWithoutFile =
        candlestickService.getRequestInfoList(List.of(AU200_AUD), List.of(M15)).get(0);
    var path = Paths.get(candleRequestInfoWithoutFile.outputPaths().get(0));
    var pathSrc = Paths.get(candleRequestInfoWithoutFile.outputPaths().get(0).replace(".csv", "-src.csv"));
    tryCopy(pathSrc, path);
    var fileExists = Files.exists(path);
    var nbOfLines = tryReadAllLines(path).size();

    var candleRequestInfo =
        candlestickService.getRequestInfoList(List.of(AU200_AUD), List.of(M15)).get(0);
    candlestickService.getCandlesFor(candleRequestInfo);

    var nbOfLinesAfter = tryReadAllLines(path).size();
    tryDeleteFile(path);
    soft.assertThat(fileExists).isTrue();
    soft.assertThat(nbOfLinesAfter).isGreaterThanOrEqualTo(nbOfLines);
    soft.assertThat(Files.exists(path)).isFalse();
    soft.assertAll();
  }

  @Test
  void should_getCandlesForAUDCAD_15M_whenFileExistsDoesntExist() {
    var soft = new SoftAssertions();
    var candleRequestInfo =
        candlestickService.getRequestInfoList(List.of(AUD_CAD), List.of(M15)).get(0);
    var path = Paths.get(candleRequestInfo.outputPaths().get(0));
    var fileExists = Files.exists(path);

    candlestickService.getCandlesFor(candleRequestInfo);

    var fileExistsAfter = Files.exists(path);
    var lines = tryReadAllLines(path);
    tryDeleteFile(path);
    soft.assertThat(fileExists).isFalse();
    soft.assertThat(fileExistsAfter).isTrue();
    soft.assertThat(lines.size()).isEqualTo(MAX_CANDLE_COUNT_OANDA_API + 1);
    soft.assertThat(Files.exists(path)).isFalse();
    soft.assertAll();
  }

  @Test
  void should_getCandlesticksFromOandaAPIWithCandleCount() {
    var soft = new SoftAssertions();

    var resp = candlestickService.getCandlestickWithCount("USD_CAD", M15, 5);

    soft.assertThat(resp.getGranularity()).isEqualTo(M15);
    soft.assertThat(resp.getInstrument()).isEqualTo(USD_CAD.toString());
    soft.assertThat(resp.getCandles().size()).isEqualTo(5);
    soft.assertAll();
  }

  private static void tryDeleteFile(Path path) {
    try {
      Files.delete(path);
    } catch (Exception e) {
      log.error("Error while DELETING file during test", e);
    }
  }

  private static void tryCopy(Path pathSrc, Path pathDestination) {
    try {
      Files.copy(pathSrc, pathDestination, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      log.error("Error while COPYING file during test", e);
    }
  }

  @NotNull
  private static List<String> tryReadAllLines(Path path) {
    try {
      return Files.readAllLines(path);
    } catch (Exception e) {
      log.error("Error while reading all lines during test", e);
      return emptyList();
    }
  }

}
