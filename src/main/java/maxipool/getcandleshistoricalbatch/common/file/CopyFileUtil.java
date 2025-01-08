package maxipool.getcandleshistoricalbatch.common.file;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
    Files.delete(dstPath);
    Files.copy(srcPath, dstPath);
    assertFileLengthsEqual(srcPath, dstPath, filename);
  }

  private static void assertFileLengthsEqual(Path srcFile, Path dstFile, String fileName) throws IOException {
    if (Files.size(dstFile) != Files.size(srcFile)) {
      var message = "File size not equal for %s%n".formatted(fileName);
      log.warn(message);
      logToFile(message);
    }
  }

}
