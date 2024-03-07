package maxipool.getcandleshistoricalbatch.common.log;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.APPEND;

@Slf4j
public class LogFileUtil {

  private static final String OUTPUT_FILE = "batch-job-output.txt";
  private static final Path PATH = Paths.get(OUTPUT_FILE);

  private static final AtomicBoolean isWrittenTo = new AtomicBoolean(false);

  public static void logToFile(String message) {
    try {
      createNewFileForCurrentBatch();
      Files.writeString(PATH, message, APPEND);
    } catch (Exception e) {
      log.error("Error while trying to write to log file", e);
    }
  }

  private static void createNewFileForCurrentBatch() throws IOException {
    if (!isWrittenTo.getAndSet(true)) {
      if (Files.exists(PATH)) {
        Files.delete(PATH);
      }
      Files.createFile(PATH);
    }
  }

}
