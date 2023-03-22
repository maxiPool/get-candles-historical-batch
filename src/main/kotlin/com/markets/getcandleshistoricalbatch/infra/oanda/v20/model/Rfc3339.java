package com.markets.getcandleshistoricalbatch.infra.oanda.v20.model;

import lombok.experimental.UtilityClass;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@UtilityClass
public class Rfc3339 {

  public static final String WITH_SECOND_PRECISION = "yyyy-MM-dd'T'HH:mm:ssX";

  public static final DateTimeFormatter YMDHMS_FORMATTER =
      DateTimeFormatter.ofPattern(WITH_SECOND_PRECISION).withZone(ZoneId.of("UTC"));

}
