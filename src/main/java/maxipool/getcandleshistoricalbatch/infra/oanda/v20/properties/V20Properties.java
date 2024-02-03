package maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties;


import com.oanda.v20.account.AccountID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url       The fxTrade or fxPractice API URL
 * @param token     The OANDA API Personal Access token
 * @param accountId A valid v20 trading account ID that {@code TOKEN} has permissions to take action on
 */
@ConfigurationProperties(prefix = "infra.oanda.v20")
public record V20Properties(String devStreamUrl,
                            String prodStreamUrl,
                            String devRestUrl,
                            String prodRestUrl,
                            String token,
                            AccountID accountId,
                            Boolean isProduction,
                            CandlestickProperties candlestick) {
}
