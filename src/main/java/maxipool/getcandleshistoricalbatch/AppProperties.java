package maxipool.getcandleshistoricalbatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;
import java.util.Set;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Set<DayOfWeek> disableOnDays) {
}
