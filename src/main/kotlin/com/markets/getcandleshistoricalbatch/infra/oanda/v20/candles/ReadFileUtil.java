package com.markets.getcandleshistoricalbatch.infra.oanda.v20.candles;

import lombok.Builder;
import lombok.With;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.RandomAccessFile;

@Slf4j
@UtilityClass
public class ReadFileUtil {

  @With
  @Builder
  public record FirstAndLastLine(String first, String last) {
    public static FirstAndLastLine empty() {
      return new FirstAndLastLine("", "");
    }
  }

  public static FirstAndLastLine getFirstAndLastLineFromFile(String fileName) {
    try (RandomAccessFile file = new RandomAccessFile(fileName, "r")) {
      var fileLength = file.length();
      if (fileLength == 0) {
        return FirstAndLastLine.empty();
      }

      return FirstAndLastLine
          .builder()
          .first(readLineFromStart(file, fileLength))
          .last(readLineFromEnd(file, fileLength - 1 /* end of last line */))
          .build();
    } catch (IOException e) {
      log.error("Error while reading file: {}", fileName);
      return FirstAndLastLine.empty();
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

  @NotNull
  private static String readLineFromStart(RandomAccessFile file, long fileLength) throws IOException {
    var sb = new StringBuilder();
    var filePointer = 0L;

    while (filePointer < fileLength) {
      if (isNewLineAndReadByte(file, sb, filePointer)) {
        break;
      }
      filePointer++;
    }

    return sb.toString();
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
  private static boolean isNewLineChar(int currentByte) {
    return currentByte == 10 || currentByte == 13;
  }

}
