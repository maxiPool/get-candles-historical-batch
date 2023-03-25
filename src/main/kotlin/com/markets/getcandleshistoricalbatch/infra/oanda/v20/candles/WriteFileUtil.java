package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles.csvutil.CsvCandle.getSchemaHeader;

@Slf4j
@UtilityClass
public class WriteFileUtil {

  public static void writeStringToFile(String filePath, String contentToAppend) throws IOException {
    var path = Paths.get(filePath);
    if (!Files.exists(path)) {
      log.warn("Creating file that doesn't exist: {}", filePath);
      Files.createFile(path);
      contentToAppend = "%s\n%s".formatted(getSchemaHeader(), contentToAppend);
    }

    log.debug("Writing to file: {}", filePath);
    Files.writeString(
        path,
        contentToAppend,
        StandardOpenOption.APPEND);
    log.debug("Done with file: {}", filePath);
  }

}
