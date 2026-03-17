package com.kemalabdic.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

class DefaultAwsCliExecutorTest {

  private static final boolean IS_WINDOWS =
    System.getProperty("os.name").toLowerCase().contains("win");

  private final DefaultAwsCliExecutor executor = new DefaultAwsCliExecutor();

  private static List<String> shellCommand(final String script) {
    if (IS_WINDOWS) {
      return List.of("cmd", "/c", script);
    }
    return List.of("/bin/sh", "-c", script);
  }

  @Test
  void executeReturnsProcess() throws IOException {
    // given
    final List<String> cmd = shellCommand("echo hello");

    // when
    final Process p = executor.execute(cmd);

    // then
    assertNotNull(p);
    assertTrue(p.isAlive() || p.exitValue() >= 0);
    p.destroyForcibly();
  }

  @Test
  void executeDetachedReturnsProcess() throws IOException {
    // given
    final List<String> cmd = shellCommand("echo hello");

    // when
    final Process p = executor.executeDetached(cmd);

    // then
    assertNotNull(p);
    assertTrue(p.isAlive() || p.exitValue() >= 0);
    p.destroyForcibly();
  }

  @Test
  void executeCaptuersStdout() throws Exception {
    // given
    final List<String> cmd = shellCommand("echo test-output");

    // when
    final Process p = executor.execute(cmd);
    p.waitFor();
    final String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    // then
    assertTrue(stdout.contains("test-output"), "Should capture stdout, got: " + stdout);
  }

  @Test
  void executeReturnsNonZeroExitCode() throws Exception {
    // given
    final List<String> cmd = IS_WINDOWS
      ? List.of("cmd", "/c", "exit /b 42")
      : List.of("/bin/sh", "-c", "exit 42");

    // when
    final Process p = executor.execute(cmd);
    final int exitCode = p.waitFor();

    // then
    assertEquals(42, exitCode, "Should return non-zero exit code");
  }

  @Test
  void executeMergesStderrIntoStdout() throws Exception {
    // given: write to stderr - redirectErrorStream(true) should merge it into stdout
    final List<String> cmd = IS_WINDOWS
      ? List.of("cmd", "/c", "echo error-msg 1>&2")
      : List.of("/bin/sh", "-c", "echo error-msg >&2");

    // when
    final Process p = executor.execute(cmd);
    p.waitFor();
    final String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    // then
    assertTrue(output.contains("error-msg"),
      "stderr should be merged into stdout via redirectErrorStream, got: " + output);
  }

  @Test
  void executeThrowsForNonexistentCommand() {
    // given
    final List<String> cmd = List.of("nonexistent-command-abc123xyz");

    // when / then
    assertThrows(IOException.class, () -> executor.execute(cmd));
  }

  @Test
  void executeDetachedThrowsForNonexistentCommand() {
    // given
    final List<String> cmd = List.of("nonexistent-command-abc123xyz");

    // when / then
    assertThrows(IOException.class, () -> executor.executeDetached(cmd));
  }

  @Test
  void executeDetachedDiscardsOutput() throws Exception {
    // given: detached mode discards stdout and stderr
    final List<String> cmd = shellCommand("echo detached-output");

    // when
    final Process p = executor.executeDetached(cmd);
    p.waitFor();

    // then: stdout should be null/empty since it's redirected to DISCARD
    final byte[] bytes = p.getInputStream().readAllBytes();
    assertEquals(0, bytes.length, "Detached mode should discard stdout");
  }
}
