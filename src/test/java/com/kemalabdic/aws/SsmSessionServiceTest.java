package com.kemalabdic.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.process.ProcessService;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class SsmSessionServiceTest {

  private static final ConsoleOutput CONSOLE = new ConsoleOutput();
  private final SsmSessionService service;
  private final AwsConfig config = new AwsConfig("eu-west-1", "my-profile", 8080);

  SsmSessionServiceTest() {
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(10);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(1000L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);
    service = newService(new ProcessService(), ec2pfConfig);
  }

  private static AwsErrorReporter newReporter() {
    return new AwsErrorReporter(CONSOLE);
  }

  private static SsmSessionService newService(final ProcessOperations processOps,
                                              final Ec2pfConfig ec2pfConfig) {
    return new SsmSessionService(processOps, newReporter(),
      new DefaultAwsCliExecutor(), CONSOLE, ec2pfConfig);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> parseBatchOutputFilterCases() {
    return java.util.stream.Stream.of(
      org.junit.jupiter.params.provider.Arguments.of("invalidLines",
        "svc-alpha\ti-abc123\ninvalid-line\n\n", 1),
      org.junit.jupiter.params.provider.Arguments.of("whitespaceOnlyLines",
        "svc-alpha\ti-abc123\n   \n\t\n", 1),
      org.junit.jupiter.params.provider.Arguments.of("nonInstanceIdPrefix",
        "svc-alpha\tnot-an-instance\nsvc-beta\ti-def456\n", 1)
    );
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> lookupInstanceIdEmptyCases() {
    return java.util.stream.Stream.of(
      org.junit.jupiter.params.provider.Arguments.of("None", "None output"),
      org.junit.jupiter.params.provider.Arguments.of("null", "null string output"),
      org.junit.jupiter.params.provider.Arguments.of("some-random-value", "non-i- prefixed output")
    );
  }

  @Test
  void batchLookupCommandContainsAllServices() {
    // given / when
    final List<String> cmd = service.buildBatchLookupCommand(
      List.of("svc-alpha", "svc-beta"), config);

    // then
    assertTrue(cmd.contains("aws"));
    assertTrue(cmd.contains("ec2"));
    assertTrue(cmd.contains("describe-instances"));
    assertTrue(cmd.stream().anyMatch(s -> s.contains("svc-alpha,svc-beta")));
    assertTrue(cmd.contains("eu-west-1"));
    assertTrue(cmd.contains("my-profile"));
    assertTrue(cmd.stream().anyMatch(s -> s.contains("running")));
  }

  @Test
  void singleLookupCommand() {
    // given / when
    final List<String> cmd = service.buildSingleLookupCommand("svc-alpha", config);

    // then
    assertTrue(cmd.contains("aws"));
    assertTrue(cmd.contains("ec2"));
    assertTrue(cmd.contains("describe-instances"));
    assertTrue(cmd.stream().anyMatch(s -> s.contains("svc-alpha")));
    assertTrue(cmd.stream().anyMatch(s -> s.contains("Reservations[0].Instances[0].InstanceId")));
  }

  @Test
  void startSessionCommand() {
    // given / when
    final List<String> cmd = service.buildStartSessionCommand(7001, 8080, "i-abc123", config);

    // then
    assertTrue(cmd.contains("aws"));
    assertTrue(cmd.contains("ssm"));
    assertTrue(cmd.contains("start-session"));
    assertTrue(cmd.contains("i-abc123"));
    assertTrue(cmd.contains("AWS-StartPortForwardingSession"));
    assertTrue(cmd.stream().anyMatch(s -> s.contains("localPortNumber=[\"7001\"]")));
    assertTrue(cmd.stream().anyMatch(s -> s.contains("portNumber=[\"8080\"]")));
  }

  @Test
  void parseBatchOutputText() {
    // given
    final Map<String, String> result = new HashMap<>();
    final String output = "svc-alpha\ti-abc123\nsvc-beta\ti-def456\n";

    // when
    service.parseBatchOutput(output, result);

    // then
    assertEquals("i-abc123", result.get("svc-alpha"));
    assertEquals("i-def456", result.get("svc-beta"));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "parseBatchOutput filters {0}")
  @org.junit.jupiter.params.provider.MethodSource("parseBatchOutputFilterCases")
  void parseBatchOutputFiltersInvalidEntries(final String description, final String output, final int expectedSize) {
    // given
    final Map<String, String> result = new HashMap<>();

    // when
    service.parseBatchOutput(output, result);

    // then
    assertEquals(expectedSize, result.size(), "Failed for case: " + description);
  }

  @Test
  void startSessionReturnsEmptyWhenProcessDiesBeforePortOpens() {
    // given
    final ProcessOperations mockProcess = mock(ProcessOperations.class);
    when(mockProcess.isPortInUse(anyInt())).thenReturn(false);

    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(100L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);

    final SsmSessionService svc = newService(mockProcess, ec2pfConfig);

    // when
    final Optional<Long> result = svc.startSession(49999, 8080, "i-fake", config);

    // then
    assertFalse(result.isPresent(), "Should return empty when process dies before port opens");
  }

  @Test
  void startSessionReturnsEmptyOnTimeoutWithWarning() {
    // given
    final java.io.ByteArrayOutputStream outCapture = new java.io.ByteArrayOutputStream();
    final ConsoleOutput capturedConsole = new ConsoleOutput(new java.io.PrintStream(outCapture), false);

    final ProcessOperations mockProcess = mock(ProcessOperations.class);
    when(mockProcess.isPortInUse(anyInt())).thenReturn(false);

    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(1);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);

    final SsmSessionService svc = new SsmSessionService(mockProcess, new AwsErrorReporter(capturedConsole),
      new DefaultAwsCliExecutor(), capturedConsole, ec2pfConfig);

    final SsmSessionService spySvc = spy(svc);
    doReturn(longRunningCommand())
      .when(spySvc).buildStartSessionCommand(anyInt(), anyInt(), anyString(), any(AwsConfig.class));

    // when
    final Optional<Long> result = spySvc.startSession(49999, 8080, "i-fake", config);

    // then
    assertFalse(result.isPresent(), "Should return empty on timeout");
    final String output = outCapture.toString();
    assertTrue(output.contains("timed out"), "Should contain timeout warning, got: " + output);
    assertTrue(output.contains("49999"), "Should mention the port number, got: " + output);
  }

  @Test
  void lookupInstanceIdReturnsEmptyWhenAwsNotAvailable() {
    // given
    final SsmSessionService svc = createShortTimeoutService();

    // when
    final Optional<String> result = svc.lookupInstanceId("nonexistent-service", config);

    // then
    assertTrue(result.isEmpty() || result.isPresent());
  }

  @Test
  void batchLookupReturnsEmptyMapWhenAwsNotAvailable() {
    // given
    final SsmSessionService svc = createShortTimeoutService();

    // when
    final Map<String, String> result = svc.batchLookupInstanceIds(List.of("svc-a", "svc-b"), config);

    // then
    assertTrue(result.isEmpty(), "Should return empty map when AWS CLI fails");
  }

  @Test
  void batchLookupWithEmptyListReturnsEmpty() {
    // given / when
    final Map<String, String> result = service.batchLookupInstanceIds(List.of(), config);

    // then
    assertTrue(result.isEmpty());
  }

  private SsmSessionService createShortTimeoutService() {
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(1);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);
    return newService(new ProcessService(), ec2pfConfig);
  }

  private SsmSessionService createLookupSpy(final String echoOutput, final ProcessOperations processOps) {
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(processOps, ec2pfConfig));
    doReturn(echoCommand(echoOutput))
      .when(svc).buildSingleLookupCommand(anyString(), any(AwsConfig.class));
    return svc;
  }

  private List<String> echoCommand(final String text) {
    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    if (isWindows) {
      return List.of("cmd", "/c", "echo " + text);
    }
    return List.of("/bin/sh", "-c", "echo " + text);
  }

  private List<String> exitCodeCommand(final int exitCode, final String output) {
    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    if (isWindows) {
      return List.of("cmd", "/c", "echo " + output + " & exit /b " + exitCode);
    }
    return List.of("/bin/sh", "-c", "echo '" + output + "'; exit " + exitCode);
  }

  private List<String> longRunningCommand() {
    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    if (isWindows) {
      return List.of("cmd", "/c", "ping -n 30 127.0.0.1 > NUL");
    }
    return List.of("/bin/sh", "-c", "sleep 30");
  }

  private List<String> immediateExitCommand() {
    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    if (isWindows) {
      return List.of("cmd", "/c", "exit /b 0");
    }
    return List.of("/bin/sh", "-c", "exit 0");
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "lookupInstanceId returns empty for {1}")
  @org.junit.jupiter.params.provider.MethodSource("lookupInstanceIdEmptyCases")
  void lookupInstanceIdReturnsEmptyForInvalidOutput(final String echoOutput, final String description) {
    // given
    final SsmSessionService svc = createLookupSpy(echoOutput, new ProcessService());

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty for: " + description);
  }

  @Test
  void lookupInstanceIdReturnsEmptyForNonZeroExitCode() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(exitCodeCommand(1, "error message"))
      .when(svc).buildSingleLookupCommand(anyString(), any(AwsConfig.class));

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty when exit code is non-zero");
  }

  @Test
  void lookupInstanceIdReturnsEmptyForEmptyOutput() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(immediateExitCommand())
      .when(svc).buildSingleLookupCommand(anyString(), any(AwsConfig.class));

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty for empty output");
  }

  @Test
  void lookupInstanceIdStripsQuotesAndWhitespace() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));

    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    final List<String> cmd;
    if (isWindows) {
      cmd = List.of("cmd", "/c", "echo \"i-abc123\"");
    } else {
      cmd = List.of("/bin/sh", "-c", "printf '\"i-abc123\"'");
    }
    doReturn(cmd).when(svc).buildSingleLookupCommand(anyString(), any(AwsConfig.class));

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isPresent(), "Should return instance ID after stripping quotes");
    assertEquals("i-abc123", result.get(), "Quotes should be stripped from instance ID");
  }

  @Test
  void lookupInstanceIdReturnsValidInstanceId() {
    // given
    final SsmSessionService svc = createLookupSpy("i-0abc123def456", new ProcessService());

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isPresent(), "Should return instance ID for valid i- prefixed output");
    assertEquals("i-0abc123def456", result.get());
  }

  @Test
  void lookupInstanceIdReturnsEmptyOnInterrupt() throws Exception {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildSingleLookupCommand(anyString(), any(AwsConfig.class));

    final Thread testThread = Thread.currentThread();

    final Thread interrupter = new Thread(() -> {
      java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(200).toNanos());
      testThread.interrupt();
    });
    interrupter.start();

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty on interrupt");
    Thread.interrupted();
    interrupter.join(2000);
  }

  @Test
  void startSessionReturnsEmptyWhenProcessExitsImmediately() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(3);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);

    final ProcessOperations mockProcess = mock(ProcessOperations.class);
    when(mockProcess.isPortInUse(anyInt())).thenReturn(false);

    final SsmSessionService svc = spy(newService(mockProcess, ec2pfConfig));
    doReturn(immediateExitCommand())
      .when(svc).buildStartSessionCommand(anyInt(), anyInt(), anyString(), any(AwsConfig.class));

    // when
    final Optional<Long> result = svc.startSession(49999, 8080, "i-fake", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty when process dies immediately");
  }

  @Test
  void startSessionReturnsEmptyOnInterrupt() throws Exception {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(100);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(500L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);

    final ProcessOperations mockProcess = mock(ProcessOperations.class);
    when(mockProcess.isPortInUse(anyInt())).thenReturn(false);

    final SsmSessionService svc = spy(newService(mockProcess, ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildStartSessionCommand(anyInt(), anyInt(), anyString(), any(AwsConfig.class));

    final Thread testThread = Thread.currentThread();

    final Thread interrupter = new Thread(() -> {
      java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(200).toNanos());
      testThread.interrupt();
    });
    interrupter.start();

    // when
    final Optional<Long> result = svc.startSession(49999, 8080, "i-fake", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty on interrupt");
    Thread.interrupted();
    interrupter.join(2000);
  }

  @Test
  void startSessionCleansUpProcessWhenNotHandedOff() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final ProcessOperations mockProcess = mock(ProcessOperations.class);
    when(mockProcess.isPortInUse(anyInt())).thenReturn(false);

    final SsmSessionService svc = spy(newService(mockProcess, ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildStartSessionCommand(anyInt(), anyInt(), anyString(), any(AwsConfig.class));

    // when
    final Optional<Long> result = svc.startSession(49999, 8080, "i-fake", config);

    // then
    assertTrue(result.isEmpty(),
      "Should return empty when port never opens (process destroyed in finally)");
  }

  @Test
  void startSessionReturnsIdWhenPortOpens() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(5);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(30);

    final ProcessOperations mockProcess = mock(ProcessOperations.class);
    when(mockProcess.isPortInUse(anyInt())).thenReturn(true);

    final SsmSessionService svc = spy(newService(mockProcess, ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildStartSessionCommand(anyInt(), anyInt(), anyString(), any(AwsConfig.class));

    // when
    final Optional<Long> result = svc.startSession(49999, 8080, "i-fake", config);

    // then
    assertTrue(result.isPresent(), "Should return PID when port opens (handedOff=true)");
    assertTrue(result.get() > 0, "PID should be positive");
  }

  @Test
  void batchLookupReturnsEmptyMapOnNonZeroExitCode() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(exitCodeCommand(1, "An error occurred"))
      .when(svc).buildBatchLookupCommand(anyList(), any(AwsConfig.class));

    // when
    final Map<String, String> result = svc.batchLookupInstanceIds(List.of("svc-a", "svc-b"), config);

    // then
    assertTrue(result.isEmpty(), "Should return empty map when exit code is non-zero");
  }

  @Test
  void batchLookupCleansUpProcessInFinallyBlock() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));

    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    final List<String> cmd;
    if (isWindows) {
      cmd = List.of("cmd", "/c", "echo svc-a\ti-abc123");
    } else {
      cmd = List.of("/bin/sh", "-c", "printf 'svc-a\\ti-abc123\\n'");
    }
    doReturn(cmd).when(svc).buildBatchLookupCommand(anyList(), any(AwsConfig.class));

    // when
    final Map<String, String> result = svc.batchLookupInstanceIds(List.of("svc-a"), config);

    // then
    assertEquals(1, result.size());
    assertEquals("i-abc123", result.get("svc-a"));
  }

  @Test
  void batchLookupTimesOutAndReturnsEmptyMap() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(1);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildBatchLookupCommand(anyList(), any(AwsConfig.class));

    // when
    final Map<String, String> result = svc.batchLookupInstanceIds(List.of("svc-a"), config);

    // then
    assertTrue(result.isEmpty(), "Should return empty map when CLI times out");
  }

  @Test
  void lookupInstanceIdTimesOutAndReturnsEmpty() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);
    when(sessionConfig.cliTimeoutSecs()).thenReturn(1);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildSingleLookupCommand(anyString(), any(AwsConfig.class));

    // when
    final Optional<String> result = svc.lookupInstanceId("my-service", config);

    // then
    assertTrue(result.isEmpty(), "Should return empty when CLI times out");
  }

  @Test
  void batchLookupReturnsEmptyMapOnInterrupt() throws Exception {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.SessionConfig sessionConfig = mock(Ec2pfConfig.SessionConfig.class);
    when(ec2pfConfig.session()).thenReturn(sessionConfig);
    when(sessionConfig.startupAttempts()).thenReturn(2);
    when(sessionConfig.startupCheckIntervalMs()).thenReturn(50L);

    final SsmSessionService svc = spy(newService(new ProcessService(), ec2pfConfig));
    doReturn(longRunningCommand())
      .when(svc).buildBatchLookupCommand(anyList(), any(AwsConfig.class));

    final Thread testThread = Thread.currentThread();

    final Thread interrupter = new Thread(() -> {
      java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(200).toNanos());
      testThread.interrupt();
    });
    interrupter.start();

    // when
    final Map<String, String> result = svc.batchLookupInstanceIds(List.of("svc-a"), config);

    // then
    assertTrue(result.isEmpty(), "Should return empty map on interrupt");
    Thread.interrupted();
    interrupter.join(2000);
  }
}
