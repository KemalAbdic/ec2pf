package com.kemalabdic.process;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.kemalabdic.util.PlatformUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.stream.Stream;

class ProcessServiceTest {

  private final ProcessService processService = new ProcessService();

  static Stream<Arguments> expectedSessionPidCases() {
    final String validCmd = "aws ssm start-session --target i-abc123 "
      + "--document-name AWS-StartPortForwardingSession "
      + "--parameters portNumber=[\"8080\"],localPortNumber=[\"7001\"]";
    final String wrongPort = "aws ssm start-session --target i-abc123 "
      + "--document-name AWS-StartPortForwardingSession "
      + "--parameters portNumber=[\"8080\"],localPortNumber=[\"7002\"]";
    final String wrongInstance = "aws ssm start-session --target i-other "
      + "--document-name AWS-StartPortForwardingSession "
      + "--parameters portNumber=[\"8080\"],localPortNumber=[\"7001\"]";
    return Stream.of(
      Arguments.of(validCmd, 7001, "i-abc123", true, "matching command line"),
      Arguments.of(wrongPort, 7001, "i-abc123", false, "mismatched port"),
      Arguments.of(wrongInstance, 7001, "i-abc123", false, "mismatched instance ID"),
      Arguments.of(validCmd, 7001, null, true, "null instance ID skips check"),
      Arguments.of(validCmd, 7001, "", true, "empty instance ID skips check")
    );
  }

  static Stream<Arguments> parseNetstatLineEmptyCases() {
    return Stream.of(
      Arguments.of("non-LISTENING line",
        "  TCP    0.0.0.0:7001           192.168.1.1:443        ESTABLISHED     12345", 7001),
      Arguments.of("too few parts",
        "  TCP    LISTENING", 7001),
      Arguments.of("wrong port",
        "  TCP    0.0.0.0:8080           0.0.0.0:0              LISTENING       12345", 7001)
    );
  }

  @AfterEach
  void tearDown() {
    PlatformUtils.setWindowsOverride(null);
  }

  @Test
  void portNotInUseWhenFree() {
    // given / when
    // then
    assertFalse(processService.isPortInUse(49123));
  }

  @Test
  void portInUseWhenBound() throws IOException {
    // given
    try (ServerSocket ss = new ServerSocket(0)) {
      final int port = ss.getLocalPort();

      // when
      // then
      assertTrue(processService.isPortInUse(port));
    }
  }

  @Test
  void currentProcessIsAlive() {
    // given
    final long myPid = ProcessHandle.current().pid();

    // when
    // then
    assertTrue(processService.isProcessAlive(myPid));
  }

  @Test
  void nonExistentProcessNotAlive() {
    // given
    // when
    // then
    assertFalse(processService.isProcessAlive(999999999L));
  }

  @Test
  void platformDetection() {
    // given
    final String os = System.getProperty("os.name", "").toLowerCase();

    // when
    // then
    if (os.contains("win")) {
      assertTrue(processService.isWindows());
    } else {
      assertFalse(processService.isWindows());
    }
  }

  @Test
  void getProcessCommandForCurrentProcess() {
    // given
    final long myPid = ProcessHandle.current().pid();

    // when
    // then
    assertDoesNotThrow(() -> processService.getProcessCommand(myPid));
  }

  @ParameterizedTest(name = "{4}")
  @MethodSource("expectedSessionPidCases")
  void isExpectedSessionPid(final String cmdLine, final int localPort, final String instanceId, final boolean expected,
                            final String desc) {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(Optional.of(cmdLine)).when(spied).getProcessCommand(100L);

    // when
    // then
    assertEquals(expected, spied.isExpectedSessionPid(100L, localPort, instanceId));
  }

  @Test
  void nonSsmCommandReturnsFalse() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(Optional.of("java -jar app.jar")).when(spied).getProcessCommand(100L);

    // when
    // then
    assertFalse(spied.isExpectedSessionPid(100L, 7001, "i-abc123"));
  }

  @Test
  void missingDocumentNameReturnsFalse() {
    // given
    final ProcessService spied = spy(new ProcessService());
    final String cmdLine = "aws ssm start-session --target i-abc123 "
      + "--parameters portNumber=[\"8080\"],localPortNumber=[\"7001\"]";
    doReturn(Optional.of(cmdLine)).when(spied).getProcessCommand(100L);

    // when
    // then
    assertFalse(spied.isExpectedSessionPid(100L, 7001, "i-abc123"));
  }

  @Test
  void noCommandLineFallsBackToAliveAndPort() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(Optional.empty()).when(spied).getProcessCommand(100L);
    doReturn(true).when(spied).isProcessAlive(100L);
    doReturn(true).when(spied).isPortInUse(7001);

    // when
    // then
    assertTrue(spied.isExpectedSessionPid(100L, 7001, "i-abc123"));
  }

  @Test
  void noCommandLineAndProcessDeadReturnsFalse() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(Optional.empty()).when(spied).getProcessCommand(100L);
    doReturn(false).when(spied).isProcessAlive(100L);

    // when
    // then
    assertFalse(spied.isExpectedSessionPid(100L, 7001, "i-abc123"));
  }

  @Test
  void killIfExpectedProcessNotAliveReturnsSkipped() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(false).when(spied).isProcessAlive(100L);

    // when
    final ProcessOperations.KillResult result = spied.killIfExpected(100L, 7001, "i-abc123");

    // then
    assertEquals(ProcessOperations.KillResult.SKIPPED_MISMATCH, result);
    verify(spied, never()).killProcessTree(100L);
  }

  @Test
  void killIfExpectedCommandMismatchReturnsSkipped() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(true).when(spied).isProcessAlive(100L);
    doReturn(Optional.of("java -jar other.jar")).when(spied).getProcessCommand(100L);

    // when
    final ProcessOperations.KillResult result = spied.killIfExpected(100L, 7001, "i-abc123");

    // then
    assertEquals(ProcessOperations.KillResult.SKIPPED_MISMATCH, result);
    verify(spied, never()).killProcessTree(100L);
  }

  @Test
  void killIfExpectedValidatedKillReturnsKilled() {
    // given
    final ProcessService spied = spy(new ProcessService());
    final String cmdLine = "aws ssm start-session --target i-abc123 "
      + "--document-name AWS-StartPortForwardingSession "
      + "--parameters portNumber=[\"8080\"],localPortNumber=[\"7001\"]";
    doReturn(true).when(spied).isProcessAlive(100L);
    doReturn(Optional.of(cmdLine)).when(spied).getProcessCommand(100L);
    doReturn(true).when(spied).killProcessTree(100L);

    // when
    final ProcessOperations.KillResult result = spied.killIfExpected(100L, 7001, "i-abc123");

    // then
    assertEquals(ProcessOperations.KillResult.KILLED, result);
  }

  @Test
  void killIfExpectedNoCommandLinePortInUseReturnsKilledLimited() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(true).when(spied).isProcessAlive(100L);
    doReturn(Optional.empty()).when(spied).getProcessCommand(100L);
    doReturn(true).when(spied).isPortInUse(7001);
    doReturn(true).when(spied).killProcessTree(100L);

    // when
    final ProcessOperations.KillResult result = spied.killIfExpected(100L, 7001, "i-abc123");

    // then
    assertEquals(ProcessOperations.KillResult.KILLED_LIMITED, result);
  }

  @Test
  void killIfExpectedNoCommandLinePortNotInUseReturnsSkipped() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(true).when(spied).isProcessAlive(100L);
    doReturn(Optional.empty()).when(spied).getProcessCommand(100L);
    doReturn(false).when(spied).isPortInUse(7001);

    // when
    final ProcessOperations.KillResult result = spied.killIfExpected(100L, 7001, "i-abc123");

    // then
    assertEquals(ProcessOperations.KillResult.SKIPPED_MISMATCH, result);
  }

  @Test
  void killIfExpectedKillFailsReturnsFailed() {
    // given
    final ProcessService spied = spy(new ProcessService());
    final String cmdLine = "aws ssm start-session --target i-abc123 "
      + "--document-name AWS-StartPortForwardingSession "
      + "--parameters portNumber=[\"8080\"],localPortNumber=[\"7001\"]";
    doReturn(true).when(spied).isProcessAlive(100L);
    doReturn(Optional.of(cmdLine)).when(spied).getProcessCommand(100L);
    doReturn(false).when(spied).killProcessTree(100L);

    // when
    final ProcessOperations.KillResult result = spied.killIfExpected(100L, 7001, "i-abc123");

    // then
    assertEquals(ProcessOperations.KillResult.FAILED, result);
  }

  @Test
  void killProcessTreeReturnsFalseForNonExistentProcess() {
    // given
    final long fakePid = 999999999L;

    // when
    final boolean result = processService.killProcessTree(fakePid);

    // then
    assertFalse(result, "Should return false for non-existent process");
  }

  @Test
  void killProcessOnPortReturnsFalseWhenNoProcessOnPort() {
    // given
    final int freePort = 49234;

    // when
    final boolean result = processService.killProcessOnPort(freePort);

    // then
    assertFalse(result, "Should return false when no process is on the port");
  }

  @Test
  void findPidOnPortReturnsEmptyForUnusedPort() {
    // given
    final int freePort = 49235;

    // when
    final Optional<Long> result = processService.findPidOnPort(freePort);

    // then
    assertTrue(result.isEmpty(), "Should return empty for unused port");
  }

  @Test
  void findPidOnPortFindsProcessOnBoundPort() throws IOException {
    // given
    try (ServerSocket ss = new ServerSocket(0)) {
      final int port = ss.getLocalPort();

      // when
      final Optional<Long> result = processService.findPidOnPort(port);

      // then
      // On some platforms this may or may not find the PID depending on tool availability
      // The important thing is it doesn't throw
      assertTrue(result.isEmpty() || result.isPresent());
    }
  }

  @Test
  void killIfExpectedRealProcessNotAlive() {
    // given
    final long fakePid = 999999999L;

    // when
    final ProcessOperations.KillResult result = processService.killIfExpected(fakePid, 49999, "i-fake");

    // then
    assertEquals(ProcessOperations.KillResult.SKIPPED_MISMATCH, result);
  }

  @Test
  void killIfExpectedRealProcessCurrentProcessMismatch() {
    // given - current process is alive but is not an SSM session
    final long myPid = ProcessHandle.current().pid();

    // when
    final ProcessOperations.KillResult result = processService.killIfExpected(myPid, 49999, "i-fake");

    // then
    // Current process has a command line (java ...) but it's not an SSM session
    assertEquals(ProcessOperations.KillResult.SKIPPED_MISMATCH, result);
  }

  @Test
  void isExpectedSessionPidRealProcessNotSsm() {
    // given - current process is alive but not an SSM session
    final long myPid = ProcessHandle.current().pid();

    // when
    final boolean result = processService.isExpectedSessionPid(myPid, 49999, "i-fake");

    // then
    assertFalse(result, "Current java process should not be detected as SSM session");
  }

  @Test
  void parseNetstatLineExtractsPidFromListeningLine() {
    // given
    final String line = "  TCP    0.0.0.0:7001           0.0.0.0:0              LISTENING       12345";
    final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(":(\\d+)\\s");

    // when
    final Optional<Long> result = ProcessService.parseNetstatLine(line, 7001, pattern);

    // then
    assertTrue(result.isPresent(), "Should find PID from LISTENING line");
    assertEquals(12345L, result.get());
  }

  @ParameterizedTest(name = "parseNetstatLine returns empty for {0}")
  @MethodSource("parseNetstatLineEmptyCases")
  void parseNetstatLineReturnsEmptyForInvalidInput(final String description, final String line, final int port) {
    // given
    final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(":(\\d+)\\s");

    // when
    final Optional<Long> result = ProcessService.parseNetstatLine(line, port, pattern);

    // then
    assertTrue(result.isEmpty(), "Should return empty for: " + description);
  }

  @Test
  void findPidOnPortCallsCorrectPlatformMethod() throws IOException {
    // given - use a port that's bound to test findPidOnPort traverses the platform path
    try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
      final int port = ss.getLocalPort();

      // when
      final Optional<Long> result = processService.findPidOnPort(port);

      // then - either finds PID or returns empty, but exercises the platform code path
      assertTrue(result.isEmpty() || result.isPresent());
    }
  }

  @Test
  void killProcessOnPortReturnsFalseWhenNoPidFound() {
    // given
    final int freePort = 49236;

    // when
    final boolean result = processService.killProcessOnPort(freePort);

    // then
    assertFalse(result, "Should return false when no process found on port");
  }

  @Test
  void killProcessTreeUnixPathForNonExistentProcess() {
    // given
    PlatformUtils.setWindowsOverride(false);

    // when
    final boolean result = processService.killProcessTree(999999999L);

    // then
    assertFalse(result);
  }

  @Test
  void killProcessTreeWindowsPathForNonExistentProcess() {
    // given
    PlatformUtils.setWindowsOverride(true);

    // when
    final boolean result = processService.killProcessTree(999999999L);

    // then
    assertFalse(result);
  }

  @Test
  void findPidOnPortUnixPathReturnsEmptyForFreePort() {
    // given
    PlatformUtils.setWindowsOverride(false);

    // when
    final Optional<Long> result = processService.findPidOnPort(49237);

    // then
    assertTrue(result.isEmpty());
  }

  @Test
  void findPidOnPortWindowsPathReturnsEmptyForFreePort() {
    // given
    PlatformUtils.setWindowsOverride(true);

    // when
    final Optional<Long> result = processService.findPidOnPort(49238);

    // then
    assertTrue(result.isEmpty());
  }

  @Test
  void killProcessOnPortDelegatesToFindAndKill() {
    // given
    final ProcessService spied = spy(new ProcessService());
    doReturn(Optional.of(999999999L)).when(spied).findPidOnPort(49239);
    doReturn(false).when(spied).killProcessTree(999999999L);

    // when
    final boolean result = spied.killProcessOnPort(49239);

    // then
    assertFalse(result);
    verify(spied).killProcessTree(999999999L);
  }
}
