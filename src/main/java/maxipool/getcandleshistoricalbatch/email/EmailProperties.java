package maxipool.getcandleshistoricalbatch.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.mail")
public record EmailProperties(String username, String password, String recipient) {
}
