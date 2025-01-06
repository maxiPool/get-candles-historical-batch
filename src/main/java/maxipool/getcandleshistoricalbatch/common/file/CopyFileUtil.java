package maxipool.getcandleshistoricalbatch.common.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.csv.CsvCandle;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.ArrayList;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.util.Optional.ofNullable;
import static maxipool.getcandleshistoricalbatch.common.csv.CsvUtil.csvStringWithoutHeaderToCsvCandlePojo;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.getLastLineFromCsvCandleFile;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.isNewLineChar;
import static maxipool.getcandleshistoricalbatch.common.log.LogFileUtil.logToFile;

@Slf4j
@UtilityClass
public class CopyFileUtil {

  public static void copyToSecondDisk(Path srcPath, String copyDst, String instrument, String granularity, String filename) throws IOException {
    var subDir = Paths.get(copyDst, instrument, granularity);
    try {
      Files.createDirectories(subDir);
    } catch (IOException e) {
      log.warn("Cannot create directory '{}'", subDir, e);
      throw e;
    }
    var dstPath = subDir.resolve(filename);
    doCopy(dstPath, srcPath);
    assertFileLengthsEqual(srcPath, dstPath, filename);
  }

  private static void doCopy(Path dstFile, Path srcFile) throws IOException {
    if (!Files.exists(dstFile)) {
      Files.copy(srcFile, dstFile);
      log.info("File created and contents copied successfully.");
    } else {
      copyMissingLines(dstFile, srcFile);
    }
  }

  private static void assertFileLengthsEqual(Path srcFile, Path dstFile, String fileName) throws IOException {
    if (Files.size(dstFile) != Files.size(srcFile)) {
      var message = "File size not equal for %s%n".formatted(fileName);
      log.warn(message);
      logToFile(message);
    }
  }

  private static void copyMissingLines(Path dstFile, Path srcFile) throws IOException {
    var lastLineDst = getTimestampOfLastCandle(dstFile);
    var lastLineSrc = getTimestampOfLastCandle(srcFile);
    if (lastLineSrc.timestamp().isAfter(lastLineDst.timestamp())) {
      var until = lastLineDst.timestamp().until(lastLineSrc.timestamp(), HOURS);
      if (until > 100) {
        Files.delete(dstFile);
        Files.copy(srcFile, dstFile);
        log.info(
            "File created and contents copied successfully (existing file '{}', but {} hours away from last timestamp)",
            dstFile, until);
      } else {
        log.info("Copying missing lines for {}, {} hours missing", dstFile, until);
        var linesToCopy = getLinesToCopy(srcFile.toString(), lastLineDst.lastLine());
        Files.writeString(dstFile, linesToCopy, CREATE, APPEND);
      }
    }
  }

  private record LastLineTimestamp(@NotNull ZonedDateTime timestamp, @NotBlank String lastLine) {
  }

  @NotNull
  private static LastLineTimestamp getTimestampOfLastCandle(Path filePath) {
    var line = getLastLineFromCsvCandleFile(filePath.toString());
    var maybeCandle = csvStringWithoutHeaderToCsvCandlePojo(line);
    return ofNullable(maybeCandle)
        .map(CsvCandle::getTime)
        .map(i -> new LastLineTimestamp(i, line))
        .orElseThrow(() -> new IllegalStateException("There should be a candle!"));
  }

  private static String getLinesToCopy(String filePath, String searchLineStart) throws IOException {
    var lines = new ArrayList<String>();
    try (var raf = new RandomAccessFile(filePath, "r")) {
      var position = raf.length() - 1;

      // Search backward for the line starting with the given string
      while (position >= 0) {
        raf.seek(position);
        var read = raf.read();
        if (isNewLineChar(read)) {
          var line = raf.readLine();
          if (line != null && line.startsWith(searchLineStart)) {
            return String.join("", lines.reversed());
          } else if (line != null && !line.isBlank()) {
            lines.add(Character.toString(read));
            lines.add(line);
          }
        }
        position--;
      }
      throw new IllegalStateException("Matching Line not found for timestamp %s".formatted(searchLineStart));
    }
  }

}
