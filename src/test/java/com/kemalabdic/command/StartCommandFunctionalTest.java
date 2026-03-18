package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kemalabdic.aws.AwsErrorReporter;
import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
import com.kemalabdic.session.SessionManager;
import com.kemalabdic.session.WatchMode;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import picocli.CommandLine.ExitCode;

class StartCommandFunctionalTest {

  @TempDir
  Path tempDir;
  private IniConfigParser configParser;
  private PidFileManager pidFileManager;
  private ProcessOperations processService;
  private SessionManager sessionManager;
  private WatchMode watchMode;
  private AwsErrorReporter errorReporter;
  private StartCommand command;
  private PrintStream originalOut;
  private ByteArrayOutputStream capturedOut;

  @BeforeEach
  void setUp() {
    configParser = new IniConfigParser();
    pidFileManager = new PidFileManager(tempDir.toString());
    processService = mock(ProcessOperations.class);
    sessionManager = mock(SessionManager.class);
    watchMode = mock(WatchMode.class);
    errorReporter = mock(AwsErrorReporter.class);
    originalOut = System.out;
    capturedOut = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));

    final Ec2pfConfig.WatchConfig watchConfig = mock(Ec2pfConfig.WatchConfig.class);
    when(watchConfig.defaultIntervalSecs()).thenReturn(30);
    when(watchConfig.minIntervalSecs()).thenReturn(5);
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    when(ec2pfConfig.watch()).thenReturn(watchConfig);

    command = new StartCommand(configParser, pidFileManager, processService, sessionManager, watchMode, errorReporter,
      new ConsoleOutput(new PrintStream(capturedOut), false), ec2pfConfig);
    command.dryRun = false;
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  private Path createConfigFile(final String... serviceLines) throws IOException {
    final StringBuilder sb = new StringBuilder();
    sb.append("[aws]\n");
    sb.append("region = eu-west-1\n");
    sb.append("profile = test\n");
    sb.append("remote_port = 8080\n\n");
    sb.append("[services]\n");
    for (final String line : serviceLines) {
      sb.append(line).append('\n');
    }
    final Path configFile = tempDir.resolve("config.ini");
    Files.writeString(configFile, sb.toString());
    return configFile;
  }

  @Test
  void callReturnsSoftwareWhenConfigFileIsNull() {
    // given
    command.configFile = null;

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsSoftwareWhenConfigFileNotFound() {
    // given
    command.configFile = tempDir.resolve("nonexistent.ini");

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsSoftwareWhenConfigParseError() throws IOException {
    // given - file with no [aws] section triggers ConfigParseException
    final Path configFile = tempDir.resolve("bad-config.ini");
    Files.writeString(configFile, "[services]\nsvc-a = 7001, false\n");
    command.configFile = configFile;

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsSoftwareWhenPidFileCreationFails() throws IOException {
    // given - use a PidFileManager pointing to a non-writable location (a file, not a directory)
    final Path fakeDir = tempDir.resolve("not-a-dir.txt");
    Files.writeString(fakeDir, "I am a file, not a directory");
    final PidFileManager brokenPfm = new PidFileManager(fakeDir.toString());

    final Ec2pfConfig.WatchConfig watchConfig = mock(Ec2pfConfig.WatchConfig.class);
    when(watchConfig.defaultIntervalSecs()).thenReturn(30);
    when(watchConfig.minIntervalSecs()).thenReturn(5);
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    when(ec2pfConfig.watch()).thenReturn(watchConfig);

    final StartCommand brokenCmd = new StartCommand(configParser, brokenPfm, processService, sessionManager,
      watchMode, errorReporter, new ConsoleOutput(new PrintStream(capturedOut), false), ec2pfConfig);
    brokenCmd.dryRun = false;

    final Path configFile = createConfigFile("svc-a = 7001, false");
    brokenCmd.configFile = configFile;

    // when
    final int exitCode = brokenCmd.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsSoftwareWhenAllServicesFail() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.FAILED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsOkWhenServicesConnect() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of("svc-a", "i-abc"));
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void callReturnsOkWithMixedResults() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false", "svc-b = 7002, false");
    command.configFile = configFile;

    final PortForwardConfig config = configParser.parse(configFile);
    final ServiceConfig svc1 = config.services().get(0);
    final ServiceConfig svc2 = config.services().get(1);

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(eq(svc1), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);
    when(sessionManager.startSession(eq(svc2), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.FAILED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void dryRunModeDoesNotStartWatchMode() throws IOException {
    // given
    command.dryRun = true;
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode, never()).run(any(), any(), anyInt());
  }

  @Test
  void noWatchFlagDisablesWatchMode() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    final StartCommand.WatchGroup wg = new StartCommand.WatchGroup();
    wg.noWatch = true;
    command.watchGroup = wg;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode, never()).run(any(), any(), anyInt());
  }

  @Test
  void watchModeStartsWithDefaultInterval() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    final PortForwardConfig config = configParser.parse(configFile);
    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode).run(config, pidFile, 30);
  }

  @Test
  void watchModeStartsWithCustomInterval() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    final StartCommand.WatchGroup wg = new StartCommand.WatchGroup();
    wg.watchInterval = 60;
    command.watchGroup = wg;

    final PortForwardConfig config = configParser.parse(configFile);
    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode).run(config, pidFile, 60);
  }

  @Test
  void watchModeIntervalBelowFiveClampsToFive() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    final StartCommand.WatchGroup wg = new StartCommand.WatchGroup();
    wg.watchInterval = 2;
    command.watchGroup = wg;

    final PortForwardConfig config = configParser.parse(configFile);
    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode).run(config, pidFile, 5);
  }

  @Test
  void cleanupPreviousKillsAliveProcessesAndDeletesPidFile() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    // Write a real PID file with a previous session entry
    final PortForwardConfig config = configParser.parse(configFile);
    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());
    pidFileManager.ensureHeader(pidFile, config.configFilePath().toString(), "config");
    pidFileManager.appendEntry(pidFile, new SessionInfo(9999L, "old-svc", 7001, 8080, "i-old", 1000L));

    when(processService.isProcessAlive(9999L)).thenReturn(true);
    when(processService.killIfExpected(9999L, 7001, "i-old"))
      .thenReturn(ProcessOperations.KillResult.KILLED);

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService).killIfExpected(9999L, 7001, "i-old");
    // PID file is recreated by ensurePidFile after cleanup, so verify entries are fresh (no old entry)
    final java.util.List<SessionInfo> entries = pidFileManager.readEntries(pidFile);
    assertTrue(entries.stream().noneMatch(e -> e.pid() == 9999L), "Old session should have been cleaned up");
  }

  @Test
  void cleanupPreviousLogsWarningOnPidMismatch() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    final PortForwardConfig config = configParser.parse(configFile);
    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());
    pidFileManager.ensureHeader(pidFile, config.configFilePath().toString(), "config");
    pidFileManager.appendEntry(pidFile, new SessionInfo(9999L, "old-svc", 7001, 8080, "i-old", 1000L));

    when(processService.isProcessAlive(9999L)).thenReturn(true);
    when(processService.killIfExpected(9999L, 7001, "i-old"))
      .thenReturn(ProcessOperations.KillResult.SKIPPED_MISMATCH);

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService).killIfExpected(9999L, 7001, "i-old");
    final String output = capturedOut.toString();
    assertTrue(output.contains("mismatch"), "Should warn about PID mismatch, got: " + output);
  }

  @Test
  void cleanupPreviousHandlesNoPreviousSessionsGracefully() throws IOException {
    // given - no pre-existing PID file; cleanup is a no-op and start succeeds
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void watchModeDoesNotStartWhenNoSessionsSucceeded() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.SKIPPED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode, never()).run(any(), any(), anyInt());
  }

  @Test
  void watchGroupWithIntervalStillStartsWatchMode() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    final StartCommand.WatchGroup wg = new StartCommand.WatchGroup();
    wg.noWatch = false;
    wg.watchInterval = 45;
    command.watchGroup = wg;

    final PortForwardConfig config = configParser.parse(configFile);
    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(watchMode).run(config, pidFile, 45);
  }

  @Test
  void callReturnsOkWhenServiceIsSkipped() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-skip = 7001, true");
    command.configFile = configFile;

    final PortForwardConfig config = configParser.parse(configFile);
    final ServiceConfig skippedSvc = config.services().get(0);

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(eq(skippedSvc), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.SKIPPED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String output = capturedOut.toString();
    assertTrue(output.contains("0 connected"), "Should display connected count, got: " + output);
    assertTrue(output.contains("1 skipped"), "Should display skipped count, got: " + output);
  }

  @Test
  void printResultSummaryShowsSkippedCount() throws IOException {
    // given
    final Path configFile = createConfigFile("svc-a = 7001, false", "svc-b = 7002, true");
    command.configFile = configFile;

    final PortForwardConfig config = configParser.parse(configFile);
    final ServiceConfig svc1 = config.services().get(0);
    final ServiceConfig svc2 = config.services().get(1);

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(eq(svc1), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);
    when(sessionManager.startSession(eq(svc2), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.SKIPPED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String output = capturedOut.toString();
    assertTrue(output.contains("1 connected"), "Should display connected count, got: " + output);
    assertTrue(output.contains("1 skipped"), "Should display skipped count, got: " + output);
  }

  @Test
  void cleanupPreviousSkipsWhenNoPreviousSessions() throws IOException {
    // given - no pre-existing PID file means readEntries returns empty list
    final Path configFile = createConfigFile("svc-a = 7001, false");
    command.configFile = configFile;

    when(sessionManager.prefetchInstanceIds(anyList(), any())).thenReturn(Map.of());
    when(sessionManager.startSession(any(), any(), any(), any(), anyBoolean()))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
  }
}
