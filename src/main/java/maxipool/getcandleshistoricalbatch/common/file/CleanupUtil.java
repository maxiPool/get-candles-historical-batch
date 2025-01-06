package maxipool.getcandleshistoricalbatch.common.file;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.csv.CsvCandle;
import maxipool.getcandleshistoricalbatch.common.csv.CsvUtil;
import org.apache.commons.lang3.ObjectUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.groupingBy;
import static maxipool.getcandleshistoricalbatch.common.csv.CsvUtil.candlesToCsvWithHeader;
import static maxipool.getcandleshistoricalbatch.common.file.WriteFileUtil.writeToFileThatDoesntExist;
import static maxipool.getcandleshistoricalbatch.common.log.LogFileUtil.logToFile;

@Slf4j
@UtilityClass
public class CleanupUtil {

  public static void cleanup(String fileName, Path path) throws IOException {
    var lines = Files.readAllLines(path);

    var candles = getCandles(fileName, lines);
    var duplicateTimes = candles
        .stream()
        .collect(groupingBy(CsvCandle::getTime))
        .values().stream()
        .filter(i -> i.size() > 1).map(i -> i.getFirst().getTime())
        .toList();
    if (!duplicateTimes.isEmpty()) {
      var instrumentHasDuplicates = "instrument %s has %d duplicates: %s".formatted(
          fileName, duplicateTimes.size(), duplicateTimes.stream().limit(5).toList());
      logToFile(instrumentHasDuplicates);
      log.warn(instrumentHasDuplicates);
      var distinctCandles = candles.stream().distinct().toArray(CsvCandle[]::new);
      var csvWithHeader = candlesToCsvWithHeader(distinctCandles);
      Files.deleteIfExists(path);
      writeToFileThatDoesntExist(path, csvWithHeader);
    } else if (candles.size() < lines.size() - 1) {
      var candlesNotDeserializable = "there are candles that were not deserializable for %s".formatted(fileName);
      log.warn(candlesNotDeserializable);
      logToFile(candlesNotDeserializable);
      var csvWithHeader = candlesToCsvWithHeader(candles.stream().distinct().toArray(CsvCandle[]::new));
      Files.deleteIfExists(path);
      writeToFileThatDoesntExist(path, csvWithHeader);
    }
  }

  private static List<CsvCandle> getCandles(String fileName, List<String> lines) {
    if (fileName.contains("M1")) {
      return lines
          .stream()
          .skip(1)
          .map(CsvUtil::csvStringWithoutHeaderToCsvCandlePojo)
          .filter(Objects::nonNull)
          .filter(i -> ObjectUtils.allNotNull(i.getTime(), i.getOpen(), i.getHigh(), i.getLow(), i.getClose(), i.getVolume(), i.getIsComplete()))
          .sorted()
          .toList();
    } else {
      return lines
          .stream()
          .skip(1)
          .map(CsvUtil::csvStringWithoutHeaderToCsvCandlePojo)
          .takeWhile(Objects::nonNull)
          .sorted()
          .toList();
    }
  }

}
