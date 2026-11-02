package maxipool.getcandleshistoricalbatch.common.build;

import maxipool.getcandleshistoricalbatch.common.build.JarFreshnessCheck.Result;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JarFreshnessCheckTest {

  private static JarFreshnessCheck withDiffExit(int diffExit, List<List<String>> calls) {
    return new JarFreshnessCheck(args -> {
      calls.add(args);
      return args.contains("rev-parse") ? new Result(0, "headsha\n") : new Result(diffExit, "");
    });
  }

  @Test
  void matchingSourcesPass() {
    var calls = new ArrayList<List<String>>();
    assertTrue(withDiffExit(0, calls).check("/repo", "abc123").isEmpty());
    assertEquals(List.of("-C", "/repo", "diff", "--quiet", "abc123", "HEAD", "--", "src", "pom.xml"), calls.get(1));
  }

  @Test
  void changedSourcesAreStale() {
    var problem = withDiffExit(1, new ArrayList<>()).check("/repo", "abc123").orElseThrow();
    assertTrue(problem.contains("Stale jar") && problem.contains("abc123") && problem.contains("headsha"), problem);
  }

  @Test
  void unknownCommitIsAProblem() {
    var problem = withDiffExit(128, new ArrayList<>()).check("/repo", "deadbeef").orElseThrow();
    assertTrue(problem.contains("git exit 128"), problem);
  }

  @Test
  void jarWithoutBuildCommitIsStale() {
    for (var commit : new String[]{null, "", "unknown", "@git.commit@"}) {
      var calls = new ArrayList<List<String>>();
      assertTrue(withDiffExit(0, calls).check("/repo", commit).orElseThrow().contains("no build commit"));
      assertTrue(calls.isEmpty());
    }
  }

  @Test
  void gitFailureIsAProblem() {
    var check = new JarFreshnessCheck(args -> {
      throw new IOException("git not found");
    });
    assertTrue(check.check("/repo", "abc").orElseThrow().contains("git not found"));
  }

  /** Against a real throwaway repository: a docs-only commit is fine, a src change makes the jar stale. */
  @Test
  void realGitDocsCommitIsFineSrcCommitIsStale(@org.junit.jupiter.api.io.TempDir Path repo) throws Exception {
    var git = JarFreshnessCheck.processGit();
    var r = repo.toString();
    run(git, "-C", r, "init", "-q");
    run(git, "-C", r, "config", "user.email", "t@t");
    run(git, "-C", r, "config", "user.name", "t");
    Files.createDirectories(repo.resolve("src"));
    Files.writeString(repo.resolve("src/A.java"), "a");
    Files.writeString(repo.resolve("pom.xml"), "p");
    run(git, "-C", r, "add", "-A");
    run(git, "-C", r, "commit", "-qm", "one");
    var built = git.run(List.of("-C", r, "rev-parse", "HEAD")).stdout().strip();
    var check = new JarFreshnessCheck(git);
    assertTrue(check.check(r, built).isEmpty());

    Files.writeString(repo.resolve("README.md"), "docs");
    run(git, "-C", r, "add", "-A");
    run(git, "-C", r, "commit", "-qm", "docs");
    assertTrue(check.check(r, built).isEmpty(), "docs-only commits do not make the jar stale");

    Files.writeString(repo.resolve("src/A.java"), "b");
    run(git, "-C", r, "commit", "-qam", "src");
    assertTrue(check.check(r, built).orElseThrow().contains("Stale jar"));
  }

  private static void run(JarFreshnessCheck.Git git, String... args) throws Exception {
    var res = git.run(List.of(args));
    assertEquals(0, res.exitCode(), res.stdout());
  }
}
