package maxipool.getcandleshistoricalbatch.common.file;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.stream.Collectors.groupingBy;
import static reactor.core.scheduler.Schedulers.boundedElastic;

@Slf4j
public class MonthlyFilesMigration {

  private static final String SRC_PATH = "F:\\candles";
  private static final String DST_PATH = "F:\\candles-monthly";
  private static final String DST_PATH_COPY = "C:\\Users\\Max\\Documents\\candles-monthly";
  private static final String LOCK_FILE_NAME = "a_lock_file.txt";

  private static final Pattern CANDLE_FILE_REGEXP = Pattern.compile("(.+)-candles-(M1|M15)\\.csv");

  public static void main(String[] args) throws IOException {
    log.info("Migration to Monthly Files Starting...");
    var processedCount = new AtomicInteger(0);

    var outDir = Paths.get(DST_PATH);
    Files.createDirectories(outDir);
    Files.createFile(outDir.resolve(LOCK_FILE_NAME));

    Flux.using(
            () -> Files.list(Paths.get(SRC_PATH)),
            Flux::fromStream,
            Stream::close
        )
        .filter(Files::isRegularFile)
        .parallel()
        .runOn(boundedElastic())
        .flatMap(path -> {
          var fileName = path.getFileName().toString();
          var matcher = CANDLE_FILE_REGEXP.matcher(fileName);
          if (!matcher.matches()) {
            log.warn("No match found for filename: {}", fileName);
            return Mono.empty();
          }

          var instrument = matcher.group(1);
          var granularity = matcher.group(2);

          return Mono.fromCallable(() -> {
                // read & process lines in a blocking way, but that's OK if we run on boundedElastic
                var lines = Files.readAllLines(path);

                var linesByMonth = lines.stream()
                    // skip CSV column header
                    .skip(1)
                    .collect(groupingBy(line -> {
                      var dateStr = line.split(",")[0];
                      var zonedDateTime = ZonedDateTime.parse(dateStr, ISO_DATE_TIME);
                      return YearMonth.from(zonedDateTime);
                    }));

                var subDir = Paths.get(DST_PATH, instrument, granularity);
                var subDirCopy = Paths.get(DST_PATH_COPY, instrument, granularity);
                Files.createDirectories(subDir);
                Files.createDirectories(subDirCopy);

                for (var entry : linesByMonth.entrySet()) {
                  var ym = entry.getKey();
                  var monthlyLines = entry.getValue();
                  // add csv header to every file
                  monthlyLines.addFirst(lines.getFirst());
                  var outFileName = String.format(
                      "%s-%s-%04d_%02d.csv",
                      instrument,
                      granularity,
                      ym.getYear(),
                      ym.getMonthValue()
                  );
                  var outFilePath = subDir.resolve(outFileName);
                  Files.write(outFilePath, monthlyLines);
                  var outFilePathCopy = subDir.resolve(outFileName);
                  Files.write(outFilePathCopy, monthlyLines);
                  // log.info("Wrote {} lines to {}", monthlyLines.size(), outFilePath);
                }

                return fileName;
              })
              .onErrorResume(e -> {
                log.error("Error processing file: {}", fileName, e);
                return Mono.empty();
              });
        })
        .doOnNext(ignored -> {
          var current = processedCount.incrementAndGet();
          if (current % 10 == 0) log.info("Processed {} files", current);
        })
        .sequential()
        // block until done
        .blockLast();

    log.info("Migration to Monthly Files Complete!");
  }

  // TODO: add tests to confirm that
  //  all the candles from the source are found in the destination
  //  the candles are ordered
  //  there are no duplicate candles (by time)

}
