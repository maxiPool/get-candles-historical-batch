package maxipool.getcandleshistoricalbatch;

import maxipool.getcandleshistoricalbatch.infra.oanda.v20.candles.InstrumentsService;
import com.oanda.v20.primitives.Instrument;
import com.oanda.v20.primitives.InstrumentName;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static java.util.stream.Collectors.*;

@Disabled
@Slf4j
@SpringBootTest
@ActiveProfiles({"local"})
public class DemoInstrumentsTest {

  @MockBean
  private OnAppReadyManager onAppReadyManager;

  @Autowired
  private InstrumentsService instrumentsService;

  @Test
  void should_getInstruments() {
    var soft = new SoftAssertions();

    var instruments = instrumentsService.findAll();

    log.info("Instruments:\n{}", instruments
        .stream()
        .collect(collectingAndThen(groupingBy(Instrument::getType),
            groups -> groups
                .entrySet().stream()
                .map(e -> e
                    .getKey() + "\n" +
                    e.getValue().stream()
                        .map(Instrument::getName)
                        .map(InstrumentName::toString)
                        .sorted()
                        .map(i -> "    " + i)
                        .collect(joining("\n")))))
        .collect(joining("\n\n")));

    soft.assertThat(instruments).isNotEmpty();
    soft.assertAll();
  }

}
