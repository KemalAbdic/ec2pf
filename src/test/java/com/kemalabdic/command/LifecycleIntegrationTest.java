package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kemalabdic.aws.AwsErrorReporter;
import com.kemalabdic.aws.SsmOperations;
import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.process.ProcessOperations.KillResult;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
import com.kemalabdic.session.SessionManager;
import com.kemalabdic.session.WatchMode;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.ExitCode;

class LifecycleIntegrationTest {

  @TempDir
  Path tempDir;
  private SsmOperations ssmOps;
  private ProcessOperations processOps;
  private ConsoleOutput console;
  private ByteArrayOutputStream capturedOut;
  private IniConfigParser configParser;
  private PidFileManager pidFileManager;
  private Ec2pfConfig ec2pfConfig;

  private static Ec2pfConfig mockEc2pfConfig() {
    final Ec2pfConfig config = mock(Ec2pfConfig.class);
    final Ec2pfConfig.WatchConfig watchConfig = mock(Ec2pfConfig.WatchConfig.class);
    when(watchConfig.defaultIntervalSecs()).thenReturn(30);
    when(watchConfig.minIntervalSecs()).thenReturn(5);
    when(watchConfig.maxBackoffSecs()).thenReturn(60);
    when(watchConfig.portReleaseAttempts()).thenReturn(10);
    when(watchConfig.portReleaseIntervalMs()).thenReturn(500L);
    when(config.watch()).thenReturn(watchConfig);
    return config;
  }

  @BeforeEach
  void setUp() {
    ssmOps = mock(SsmOperations.class);
    processOps = mock(ProcessOperations.class);
    capturedOut = new ByteArrayOutputStream();
    console = new ConsoleOutput(new PrintStream(capturedOut), false);
    configParser = new IniConfigParser();
    pidFileManager = new PidFileManager(tempDir.toString());
    ec2pfConfig = mockEc2pfConfig();
  }

  private Path writeConfigFile(final String content) throws Exception {
    final Path configFile = tempDir.resolve("test-config.ini");
    Files.writeString(configFile, content);
    return configFile;
  }

  private String configWithTwoServices() {
    return """
      [aws]
      region = eu-west-1
      profile = test-profile
      remote_port = 8080

      [services]
      svc-alpha = 17001, false
      svc-beta = 17002, false
      """;
  }

  private StartCommand createStartCommand() {
    final SessionManager sessionManager = new SessionManager(
      ssmOps, processOps, pidFileManager, console);
    final WatchMode watchMode = mock(WatchMode.class);
    final AwsErrorReporter errorReporter = new AwsErrorReporter(console);
    final StartCommand cmd = new StartCommand(
      configParser, pidFileManager, processOps, sessionManager,
      watchMode, errorReporter, console, ec2pfConfig);
    cmd.dryRun = false;
    // Disable watch mode for integration test
    final StartCommand.WatchGroup wg = new StartCommand.WatchGroup();
    wg.noWatch = true;
    cmd.watchGroup = wg;
    return cmd;
  }

  private StatusCommand createStatusCommand() {
    final StatusCommand cmd = new StatusCommand(
      configParser, pidFileManager, processOps, console);
    final StatusCommand.StatusTarget target = new StatusCommand.StatusTarget();
    cmd.target = target;
    return cmd;
  }

  private StopCommand createStopCommand() {
    return new StopCommand(configParser, pidFileManager, processOps, console);
  }

  @Test
  void fullLifecycleWithTwoServices() throws Exception {
    // given
    final Path configFile = writeConfigFile(configWithTwoServices());
    final long fakePid1 = 99901L;
    final long fakePid2 = 99902L;

    when(ssmOps.batchLookupInstanceIds(any(), any()))
      .thenReturn(Map.of("svc-alpha", "i-aaa111", "svc-beta", "i-bbb222"));
    when(processOps.isPortInUse(17001)).thenReturn(false, true);
    when(processOps.isPortInUse(17002)).thenReturn(false, true);
    when(ssmOps.startSession(eq(17001), eq(8080), eq("i-aaa111"), any()))
      .thenReturn(Optional.of(fakePid1));
    when(ssmOps.startSession(eq(17002), eq(8080), eq("i-bbb222"), any()))
      .thenReturn(Optional.of(fakePid2));

    // when: start
    final StartCommand startCmd = createStartCommand();
    startCmd.configFile = configFile;

    final int startExit = startCmd.call();

    assertEquals(ExitCode.OK, startExit, "Start should succeed");

    // Verify PID file was created with correct entries
    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    assertTrue(Files.exists(pidFile), "PID file should exist after start");

    final List<SessionInfo> entries = pidFileManager.readEntries(pidFile);
    assertEquals(2, entries.size(), "Should have 2 session entries");
    assertEquals("svc-alpha", entries.get(0).service());
    assertEquals(fakePid1, entries.get(0).pid());
    assertEquals("i-aaa111", entries.get(0).instanceId());
    assertEquals("svc-beta", entries.get(1).service());
    assertEquals(fakePid2, entries.get(1).pid());
    assertEquals("i-bbb222", entries.get(1).instanceId());

    // then: status
    capturedOut.reset();
    when(processOps.isProcessAlive(fakePid1)).thenReturn(true);
    when(processOps.isProcessAlive(fakePid2)).thenReturn(true);
    when(processOps.isPortInUse(17001)).thenReturn(true);
    when(processOps.isPortInUse(17002)).thenReturn(true);

    final StatusCommand statusCmd = createStatusCommand();
    statusCmd.target.configFile = configFile;

    final int statusExit = statusCmd.call();

    assertEquals(ExitCode.OK, statusExit, "Status should succeed");
    final String statusOut = capturedOut.toString();
    assertTrue(statusOut.contains("svc-alpha"), "Status should show svc-alpha");
    assertTrue(statusOut.contains("svc-beta"), "Status should show svc-beta");
    assertTrue(statusOut.contains("i-aaa111"), "Status should show instance ID");
    assertTrue(statusOut.contains("up"), "Alive sessions should show 'up'");

    // then: stop
    capturedOut.reset();
    when(processOps.killIfExpected(fakePid1, 17001, "i-aaa111"))
      .thenReturn(KillResult.KILLED);
    when(processOps.killIfExpected(fakePid2, 17002, "i-bbb222"))
      .thenReturn(KillResult.KILLED);

    final StopCommand stopCmd = createStopCommand();
    stopCmd.dryRun = false;
    final StopCommand.StopTarget stopTarget = new StopCommand.StopTarget();
    stopTarget.configFile = configFile;
    stopCmd.target = stopTarget;

    final int stopExit = stopCmd.call();

    assertEquals(ExitCode.OK, stopExit, "Stop should succeed");
    assertFalse(Files.exists(pidFile), "PID file should be deleted after stop");

    final String stopOut = capturedOut.toString();
    assertTrue(stopOut.contains("Stopped"), "Stop output should confirm stopped");
  }

  @Test
  void startWithOneFailedServiceStillRecordsSuccessful() throws Exception {
    // given
    final Path configFile = writeConfigFile(configWithTwoServices());
    final long fakePid = 99903L;

    when(ssmOps.batchLookupInstanceIds(any(), any()))
      .thenReturn(Map.of("svc-alpha", "i-aaa111", "svc-beta", "i-bbb222"));
    when(processOps.isPortInUse(17001)).thenReturn(false, true);
    when(processOps.isPortInUse(17002)).thenReturn(false);
    when(ssmOps.startSession(eq(17001), eq(8080), eq("i-aaa111"), any()))
      .thenReturn(Optional.of(fakePid));
    when(ssmOps.startSession(eq(17002), eq(8080), eq("i-bbb222"), any()))
      .thenReturn(Optional.empty()); // session fails

    // when
    final StartCommand startCmd = createStartCommand();
    startCmd.configFile = configFile;
    final int startExit = startCmd.call();

    // then
    assertEquals(ExitCode.OK, startExit);

    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    final List<SessionInfo> entries = pidFileManager.readEntries(pidFile);
    assertEquals(1, entries.size(), "Only successful session should be in PID file");
    assertEquals("svc-alpha", entries.get(0).service());
  }

  @Test
  void statusShowsDownForDeadProcess() throws Exception {
    // given
    final Path configFile = writeConfigFile(configWithTwoServices());
    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    pidFileManager.ensureHeader(pidFile, configFile.toString(), "test-config");
    pidFileManager.appendEntry(pidFile,
      new SessionInfo(12345L, "svc-dead", 17001, 8080, "i-dead", 1000L));

    when(processOps.isProcessAlive(12345L)).thenReturn(false);
    when(processOps.isPortInUse(17001)).thenReturn(false);

    // when
    final StatusCommand statusCmd = createStatusCommand();
    statusCmd.target.configFile = configFile;
    final int exit = statusCmd.call();

    // then
    assertEquals(ExitCode.OK, exit);
    final String out = capturedOut.toString();
    assertTrue(out.contains("svc-dead"), "Should show dead service");
    assertTrue(out.contains("down"), "Dead process should show 'down'");
  }

  @Test
  void restartCleansUpPreviousSessionsAndStartsNew() throws Exception {
    // given
    final Path configFile = writeConfigFile(configWithTwoServices());
    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    pidFileManager.ensureHeader(pidFile, configFile.toString(), "test-config");
    pidFileManager.appendEntry(pidFile,
      new SessionInfo(88801L, "svc-alpha", 17001, 8080, "i-old", 1000L));

    when(processOps.isProcessAlive(88801L)).thenReturn(true);
    when(processOps.killProcessTree(88801L)).thenReturn(true);

    final long newPid = 99904L;
    when(ssmOps.batchLookupInstanceIds(any(), any()))
      .thenReturn(Map.of("svc-alpha", "i-new111", "svc-beta", "i-new222"));
    when(processOps.isPortInUse(17001)).thenReturn(false, true);
    when(processOps.isPortInUse(17002)).thenReturn(false, true);
    when(ssmOps.startSession(eq(17001), eq(8080), eq("i-new111"), any()))
      .thenReturn(Optional.of(newPid));
    when(ssmOps.startSession(eq(17002), eq(8080), eq("i-new222"), any()))
      .thenReturn(Optional.of(newPid + 1));

    // when
    final StartCommand startCmd = createStartCommand();
    startCmd.configFile = configFile;
    final int exit = startCmd.call();

    // then
    assertEquals(ExitCode.OK, exit);

    final List<SessionInfo> entries = pidFileManager.readEntries(pidFile);
    assertEquals(2, entries.size(), "Should have 2 new entries");
    assertEquals("i-new111", entries.get(0).instanceId());
    assertEquals("i-new222", entries.get(1).instanceId());
  }

  @Test
  void stopAllFindsAndStopsMultiplePidFiles() throws Exception {
    // given
    final Path config1 = writeConfigFile(configWithTwoServices());
    final Path config2 = tempDir.resolve("other-config.ini");
    Files.writeString(config2, """
      [aws]
      region = us-east-1
      profile = other
      remote_port = 9090

      [services]
      svc-gamma = 17003, false
      """);

    final Path pidFile1 = pidFileManager.pidFileFor(config1.toString());
    pidFileManager.ensureHeader(pidFile1, config1.toString(), "test-config");
    pidFileManager.appendEntry(pidFile1,
      new SessionInfo(77701L, "svc-alpha", 17001, 8080, "i-aaa", 1000L));

    final Path pidFile2 = pidFileManager.pidFileFor(config2.toString());
    pidFileManager.ensureHeader(pidFile2, config2.toString(), "other-config");
    pidFileManager.appendEntry(pidFile2,
      new SessionInfo(77702L, "svc-gamma", 17003, 9090, "i-ggg", 1000L));

    when(processOps.killIfExpected(77701L, 17001, "i-aaa"))
      .thenReturn(KillResult.KILLED);
    when(processOps.killIfExpected(77702L, 17003, "i-ggg"))
      .thenReturn(KillResult.KILLED);

    // when
    final StopCommand stopCmd = createStopCommand();
    stopCmd.dryRun = false;
    final StopCommand.StopTarget target = new StopCommand.StopTarget();
    target.all = true;
    stopCmd.target = target;

    final int exit = stopCmd.call();

    // then
    assertEquals(ExitCode.OK, exit);
    assertFalse(Files.exists(pidFile1), "First PID file should be deleted");
    assertFalse(Files.exists(pidFile2), "Second PID file should be deleted");
  }

  @Test
  void startWithSkippedServiceDoesNotRecordIt() throws Exception {
    // given
    final Path configFile = tempDir.resolve("skip-config.ini");
    Files.writeString(configFile, """
      [aws]
      region = eu-west-1
      profile = test-profile
      remote_port = 8080

      [services]
      active-svc = 17001, false
      skipped-svc = 17002, true
      """);

    final long fakePid = 99905L;
    when(ssmOps.batchLookupInstanceIds(any(), any()))
      .thenReturn(Map.of("active-svc", "i-act111"));
    when(processOps.isPortInUse(17001)).thenReturn(false, true);
    when(ssmOps.startSession(eq(17001), eq(8080), eq("i-act111"), any()))
      .thenReturn(Optional.of(fakePid));

    // when
    final StartCommand startCmd = createStartCommand();
    startCmd.configFile = configFile;
    final int exit = startCmd.call();

    // then
    assertEquals(ExitCode.OK, exit);

    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    final List<SessionInfo> entries = pidFileManager.readEntries(pidFile);
    assertEquals(1, entries.size());
    assertEquals("active-svc", entries.get(0).service());
  }
}
