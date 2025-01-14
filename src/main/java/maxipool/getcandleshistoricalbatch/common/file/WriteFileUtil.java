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
    appendStringToFile(Paths.get(filePath), contentToAppend);
  }

  public static void appendStringToFile(Path filePath, String contentToAppend) throws IOException {
    log.debug("Writing to file: {}", filePath);
    Files.writeString(filePath, contentToAppend, StandardOpenOption.APPEND);
    log.debug("Done with file: {}", filePath);
  }

  public static void writeToFileThatDoesntExist(String filePath, String content) throws IOException {
    writeToFileThatDoesntExist(Paths.get(filePath), content);
  }

  public static void writeToFileThatDoesntExist(Path filePath, String content) throws IOException {
    log.debug("Writing to file: {}", filePath);
    Files.writeString(filePath, content, StandardOpenOption.CREATE_NEW);
    log.debug("Done with file: {}", filePath);
  }

}
