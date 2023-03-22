package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class CsvUtil {

  private static final CsvMapper csvMapper;
  private static final CsvSchema csvSchemaWithHeader;
  private static final CsvSchema csvSchemaWithoutHeader;

  static {
    csvMapper = new CsvMapper();

    csvSchemaWithHeader = csvMapper
        .schemaFor(CsvCandle.class)
        .withHeader();

    csvSchemaWithoutHeader = csvMapper
        .schemaFor(CsvCandle.class)
        .withoutHeader();
  }

  public static String candleToCsv(CsvCandle candle) {
    try {
      return csvMapper
          .writerFor(CsvCandle.class)
          .with(csvSchemaWithoutHeader)
          .writeValueAsString(candle);
    } catch (JsonProcessingException e) {
      log.error("Error while converting candle to csv", e);
    }
    return null;
  }

  public static CsvCandle csvStringToCsvCandlePojo(String csvString) {
    try {
      return csvMapper
          .readerFor(CsvCandle.class)
          .with(csvSchemaWithHeader)
          .readValue(csvString);
    } catch (JsonProcessingException e) {
      log.error("Error while converting candle to csv", e);
    }
    return null;
  }

}
