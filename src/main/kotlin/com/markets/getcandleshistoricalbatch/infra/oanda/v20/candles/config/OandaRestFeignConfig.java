package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.config;

import com.markets.getcandleshistoricalbatch.infra.oanda.v20.properties.V20Properties;
import feign.Logger;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

public class OandaRestFeignConfig {

  public static final String OANDA_FEIGN = "OANDA FEIGN";

  @Bean(name = OANDA_FEIGN + " LOGGER")
  Logger.Level feignLoggerLevel() {
    return Logger.Level.FULL;
  }

  @Bean(name = OANDA_FEIGN + " INTERCEPTOR")
  public RequestInterceptor requestInterceptor(final V20Properties properties) {
    return (requestTemplate) -> {
      requestTemplate.header(AUTHORIZATION, "Bearer %s".formatted(properties.token()));
      requestTemplate.header("Accept", APPLICATION_JSON_VALUE);
      requestTemplate.header(CONTENT_TYPE, APPLICATION_JSON_VALUE);
      requestTemplate.header("Accept-Datetime-Format", "RFC3339");
    };
  }

}
