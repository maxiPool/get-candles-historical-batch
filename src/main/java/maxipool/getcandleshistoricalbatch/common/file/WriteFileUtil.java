package maxipool.getcandleshistoricalbatch.common.file;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Slf4j
@UtilityClass
public class WriteFileUtil {

  public static void appendStringToFile(String filePath, String contentToAppend) throws IOException {
    var path = Paths.get(filePath);
    log.debug("Writing to file: {}", filePath);
    Files.writeString(path, contentToAppend, StandardOpenOption.APPEND);
    log.debug("Done with file: {}", filePath);
  }

  public static void writeToFileThatDoesntExist(String filePath, String content) throws IOException {
    var path = Paths.get(filePath);
    log.debug("Writing to file: {}", filePath);
    Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
    log.debug("Done with file: {}", filePath);
  }

  public static void writeToFileThatDoesntExist(Path filePath, String content) throws IOException {
    log.debug("Writing to file: {}", filePath);
    Files.writeString(filePath, content, StandardOpenOption.CREATE_NEW);
    log.debug("Done with file: {}", filePath);
  }

}
