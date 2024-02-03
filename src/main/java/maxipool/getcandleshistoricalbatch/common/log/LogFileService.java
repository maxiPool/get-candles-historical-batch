package maxipool.getcandleshistoricalbatch.common.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.APPEND;

@Slf4j
@Service
public class LogFileService {

  private static final String OUTPUT_FILE = "batch-job-output.txt";
  private static final Path PATH = Paths.get(OUTPUT_FILE);

  private final AtomicBoolean isWrittenTo = new AtomicBoolean(false);

  public void logToFile(String message) {
    try {
      if (!isWrittenTo.getAndSet(true)) {
        if (Files.exists(PATH)) {
          Files.delete(PATH);
        }
        Files.createFile(PATH);
      }
      Files.writeString(PATH, message, APPEND);
    } catch (Exception e) {
      log.error("Error while trying to write to log file", e);
    }
  }

}
