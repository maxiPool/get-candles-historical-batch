package maxipool.getcandleshistoricalbatch.common.csv;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class CsvUtil {

  private static final CsvMapper csvMapper;
  private static final CsvSchema csvSchemaWithHeader;
  private static final CsvSchema csvSchemaWithoutHeader;
  public static final ObjectReader READER;

  static {
    csvMapper = new CsvMapper();
    csvMapper.registerModule(new JavaTimeModule());
    csvMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    csvSchemaWithHeader = csvMapper
        .schemaFor(CsvCandle.class)
        .withHeader();

    csvSchemaWithoutHeader = csvMapper
        .schemaFor(CsvCandle.class)
        .withoutHeader();

    READER = csvMapper
        .readerFor(CsvCandle.class)
        .with(csvSchemaWithoutHeader);
  }

  public static CsvCandle csvStringWithoutHeaderToCsvCandlePojo(String csvString) {
    if (csvString == null) {
      return null;
    }
    try {
      return READER.readValue(csvString);
    } catch (Exception e) {
      log.warn("Error while converting candle to csv {}", e.getMessage());
    }
    return null;
  }

  /**
   * Candles to a CSV format string, including the header.
   */
  public static String candlesToCsvWithHeader(CsvCandle[] candles) {
    return candlesToCsvHelper(candles, csvSchemaWithHeader);
  }

  /**
   * Candles to a CSV format string, WITHOUT the header.
   */
  public static String candlesToCsvWithoutHeader(CsvCandle[] candles) {
    return candlesToCsvHelper(candles, csvSchemaWithoutHeader);
  }

  @Nullable
  private static String candlesToCsvHelper(CsvCandle[] candles, CsvSchema csvSchema) {
    try {
      return csvMapper
          .writerFor(CsvCandle[].class)
          .with(csvSchema)
          .writeValueAsString(candles);
    } catch (JsonProcessingException e) {
      log.error("Error while converting candle to csv", e);
    }
    return null;
  }

}
