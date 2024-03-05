package maxipool.getcandleshistoricalbatch.common.file;

import jakarta.validation.constraints.NotNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;

@Slf4j
@UtilityClass
public class ReadFileUtil {

  @Nullable
  public static String getLastLineFromCsvCandleFile(String fileName) {
    if (!Files.exists(Paths.get(fileName))) {
      log.info("File doesn't exist: {}", fileName);
      return null;
    }
    try (var file = new RandomAccessFile(fileName, "r")) {
      var fileLength = file.length();
      if (fileLength == 0) {
        return null;
      }

      return readLineFromEnd(file, fileLength - 1 /* end of last line */);
    } catch (IOException e) {
      log.error("Error while reading file: {}", fileName, e);
      return null;
    }
  }

  /**
   * Reads a line from its end (backward) until a new line character is found or the start of the file is reached.
   */
  @NotNull
  private static String readLineFromEnd(RandomAccessFile file, long fileLength) throws IOException {
    var sb = new StringBuilder();
    var filePointer = fileLength - 1;

    while (filePointer > 0) {
      if (isNewLineAndReadByte(file, sb, filePointer)) {
        break;
      }
      filePointer--;
    }

    return sb.reverse().toString(); // \n\rolleh --> hello\r\n
  }

  private static boolean isNewLineAndReadByte(RandomAccessFile file, StringBuilder sb, long filePointer) throws IOException {
    file.seek(filePointer);
    var currentByte = file.readByte();
    if (isNewLineChar(currentByte)) {
      return true;
    }
    sb.append((char) currentByte);
    return false;
  }

  /**
   * 10 = LF = \n
   * <br />
   * 13 = CR = \r
   */
  public static boolean isNewLineChar(int currentByte) {
    return currentByte == 10 || currentByte == 13;
  }

}
