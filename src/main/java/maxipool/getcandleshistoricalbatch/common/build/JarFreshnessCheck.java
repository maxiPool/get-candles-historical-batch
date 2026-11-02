package maxipool.getcandleshistoricalbatch.common.build;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fails when the running jar was built from a commit whose src/ or pom.xml differ from the checkout's HEAD:
 * the June jar kept running for months after its fix (6eee6d8) was committed.
 */
@RequiredArgsConstructor
public class JarFreshnessCheck {

  /** Runs git with the given arguments; returns the exit code and stdout. */
  @FunctionalInterface
  public interface Git {
    Result run(List<String> args) throws IOException, InterruptedException;
  }

  public record Result(int exitCode, String stdout) {
  }

  private final Git git;

  /**
   * @return a problem description, or empty when the jar matches HEAD
   */
  public Optional<String> check(String repoDir, String buildCommit) {
    if (buildCommit == null || buildCommit.isBlank() || buildCommit.equals("unknown") || buildCommit.startsWith("@")) {
      return Optional.of("Stale jar: the jar has no build commit (value '%s'); deploy it with bin/deploy.sh".formatted(buildCommit));
    }
    try {
      var head = git.run(List.of("-C", repoDir, "rev-parse", "HEAD"));
      if (head.exitCode() != 0) {
        return Optional.of("Stale jar: cannot read HEAD of %s (git exit %d)".formatted(repoDir, head.exitCode()));
      }
      var headSha = head.stdout().strip();
      var diff = git.run(List.of("-C", repoDir, "diff", "--quiet", buildCommit, "HEAD", "--", "src", "pom.xml"));
      return switch (diff.exitCode()) {
        case 0 -> Optional.empty();
        case 1 -> Optional.of(("Stale jar: built from %s but src/ or pom.xml changed since (HEAD %s in %s); "
            + "redeploy with bin/deploy.sh").formatted(buildCommit, headSha, repoDir));
        default -> Optional.of("Stale jar: cannot compare build commit %s with HEAD %s (git exit %d)"
            .formatted(buildCommit, headSha, diff.exitCode()));
      };
    } catch (IOException e) {
      return Optional.of("Stale jar: cannot run git: %s".formatted(e.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of("Stale jar: interrupted while running git");
    }
  }

  public static Git processGit() {
    return args -> {
      var cmd = new ArrayList<String>();
      cmd.add("git");
      cmd.addAll(args);
      var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return new Result(-1, out);
      }
      return new Result(p.exitValue(), out);
    };
  }
}
