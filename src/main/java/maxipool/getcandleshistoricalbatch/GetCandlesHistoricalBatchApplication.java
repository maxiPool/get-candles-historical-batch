package maxipool.getcandleshistoricalbatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class GetCandlesHistoricalBatchApplication {

	public static void main(String[] args) {
		SpringApplication.run(GetCandlesHistoricalBatchApplication.class, args);
	}

}
