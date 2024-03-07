package maxipool.getcandleshistoricalbatch.common.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import maxipool.getcandleshistoricalbatch.common.csv.CsvCandle;
import maxipool.getcandleshistoricalbatch.infra.oanda.v20.properties.CandlestickProperties;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static maxipool.getcandleshistoricalbatch.common.csv.CsvUtil.csvStringWithoutHeaderToCsvCandlePojo;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.getLastLineFromCsvCandleFile;
import static maxipool.getcandleshistoricalbatch.common.file.ReadFileUtil.isNewLineChar;
import static maxipool.getcandleshistoricalbatch.common.log.LogFileUtil.logToFile;

@Slf4j
@UtilityClass
public class CopyFileUtil {

  public static void main(String[] args) {
    copyToSecondDisk(
        CandlestickProperties
            .builder()
            .outputPath("F:/candles")
            .copyOutputPath("C:/Users/Max/Documents/candles")
            .fileTemplateMatcher("-candles-")
            .build()
    );
  }

  public static void copyToSecondDisk(CandlestickProperties props) {
    var dstFolderPath = Paths.get(props.copyOutputPath());
    var sourceFolder = props.outputPath();
    var fileNamesFromSourceFolder = getSourceFileNames(sourceFolder, props.fileTemplateMatcher());
    var srcFolderPath = Paths.get(sourceFolder);

    try (var exe = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
      var progressCounter = new AtomicInteger(0);
      for (var i = 0; i < fileNamesFromSourceFolder.size(); i++) {
        var finalI = i;
        exe.submit(() -> {
          var fileName = fileNamesFromSourceFolder.get(finalI);
          try {
            var dstFile = dstFolderPath.resolve(fileName);
            var srcFile = srcFolderPath.resolve(fileName);
            if (!Files.exists(dstFile)) {
              Files.copy(srcFile, dstFile);
              log.info("File created and contents copied successfully.");
            } else {
              copyMissingLines(dstFile, srcFile);
            }
          } catch (IOException e) {
            log.error("Error occurred while looking for existing destination file '{}': ", fileName, e);
          }
          var counter = progressCounter.incrementAndGet();
          if (counter % 20 == 0 || counter >= fileNamesFromSourceFolder.size() - 1) {
            log.info("Copy candles files progress: {}/{}", counter, fileNamesFromSourceFolder.size());
          }
        });
      }
    }

    logToFile("Done copying files\n");
    assertFileLengthsAreEqual(fileNamesFromSourceFolder, srcFolderPath, dstFolderPath);
  }

  private static void assertFileLengthsAreEqual(List<String> fileNamesFromSourceFolder, Path srcFolderPath, Path dstFolderPath) {
    log.info("START assertFileLengthsAreEqual");
    logToFile("START assertFileLengthsAreEqual\n");
    fileNamesFromSourceFolder
        .forEach(fileName -> {
          try {
            var dstFile = dstFolderPath.resolve(fileName);
            var srcFile = srcFolderPath.resolve(fileName);
            if (Files.size(dstFile) != Files.size(srcFile)) {
              var message = "File size not equal for %s%n".formatted(fileName);
              log.warn(message);
              logToFile(message);
            }
          } catch (IOException e) {
            log.error("Unexpected error while reading file size assertion", e);
          }
        });
    log.info("DONE assertFileLengthsAreEqual");
    logToFile("DONE assertFileLengthsAreEqual\n");
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

  private static List<String> getSourceFileNames(String sourceFolder, String fileTemplate) {
    try (var directoryStream = Files.newDirectoryStream(Paths.get(sourceFolder))) {
      var results = new ArrayList<String>();
      for (var path : directoryStream) {
        if (Files.isRegularFile(path) && path.getFileName().toString().contains(fileTemplate)) {
          results.add(path.getFileName().toString());
        }
      }
      return results;
    } catch (Exception e) {
      log.error("Error while getting source file names", e);
    }
    return emptyList();
  }

  public static String getLinesToCopy(String filePath, String searchLineStart) throws IOException {
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
