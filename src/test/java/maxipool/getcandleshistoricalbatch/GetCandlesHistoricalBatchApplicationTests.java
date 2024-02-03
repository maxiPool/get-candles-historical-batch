package maxipool.getcandleshistoricalbatch;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled
@SpringBootTest(properties = {
    "infra.oanda.v20.candlestick.enabled=false"
})
class GetCandlesHistoricalBatchApplicationTests {

	@Test
	void contextLoads() {
	}

}
