package com.kemalabdic.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class WatchModeTest {

  @TempDir
  Path tempDir;
  private ProcessOperations processService;
  private PidFileManager pidFileManager;
  private SessionManager sessionManager;
  private ConsoleOutput console;
  private WatchMode watchMode;
  private AwsConfig awsConfig;
  private ServiceConfig svc;
  private PortForwardConfig config;

  @BeforeEach
  void setUp() {
    processService = mock(ProcessOperations.class);
    pidFileManager = mock(PidFileManager.class);
    sessionManager = mock(SessionManager.class);
    console = new ConsoleOutput();

    BackoffStrategy backoff = new BackoffStrategy(60);
    watchMode = new WatchMode(processService, pidFileManager, sessionManager, console, backoff);

    awsConfig = new AwsConfig("eu-west-1", "test", 8080);
    svc = new ServiceConfig("my-svc", 7001, false, 8080);
    config = new PortForwardConfig(awsConfig, List.of(svc), Path.of("/test.ini"), "test");
  }

  @Test
  void allSessionsAliveNoReconnection() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(true);

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void deadSessionTriggersReconnect() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(sessionManager).startSession(eq(svc), eq(awsConfig), any(), eq(pidFile), eq(false), eq(OutputMode.QUIET));
  }

  @Test
  void reconnectFailureIncrementsBackoff() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);
    when(sessionManager.startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class)))
      .thenReturn(SessionManager.SessionResult.FAILED);

    final Map<String, String> instanceCache = new HashMap<>();

    // when - first call: should attempt reconnect and fail
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(sessionManager).startSession(eq(svc), eq(awsConfig), any(), eq(pidFile), eq(false), eq(OutputMode.QUIET));

    // when - second call: within backoff window, should skip reconnect
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - startSession should still have been called only once (from the first call)
    verify(sessionManager).startSession(eq(svc), eq(awsConfig), any(), eq(pidFile), eq(false), eq(OutputMode.QUIET));
  }

  @Test
  void cleanupKillsAllAndDeletesPidFile() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry1, entry2));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isProcessAlive(5678L)).thenReturn(true);
    when(processService.killIfExpected(1234L, 7001, "i-abc123"))
      .thenReturn(ProcessOperations.KillResult.KILLED);
    when(processService.killIfExpected(5678L, 7002, "i-def456"))
      .thenReturn(ProcessOperations.KillResult.KILLED);

    // when
    watchMode.cleanupManagedSessions(pidFile);

    // then
    verify(processService).killIfExpected(1234L, 7001, "i-abc123");
    verify(processService).killIfExpected(5678L, 7002, "i-def456");
    verify(pidFileManager).deletePidFile(pidFile);
  }

  @Test
  void checkAndReconnectHandlesIOExceptionReadingPidFile() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    when(pidFileManager.readEntries(pidFile)).thenThrow(new java.io.IOException("corrupt file"));

    // when
    final Map<String, String> instanceCache = new HashMap<>();
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void processEntryKillsAliveProcessWithClosedPort() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(false);
    when(processService.killIfExpected(1234L, 7001, "i-abc123"))
      .thenReturn(ProcessOperations.KillResult.KILLED);

    when(sessionManager.startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class)))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final Map<String, String> instanceCache = new HashMap<>();
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(processService).killIfExpected(1234L, 7001, "i-abc123");
  }

  @Test
  void waitForPortReleaseReturnsTrueWhenPortFreeImmediately() {
    // given
    when(processService.isPortInUse(7001)).thenReturn(false);

    // when
    final boolean result = watchMode.waitForPortRelease(7001);

    // then
    org.junit.jupiter.api.Assertions.assertTrue(result, "Should return true when port is free immediately");
  }

  @Test
  void attemptReconnectDropsServiceNotFoundInConfig() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "unknown-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);

    // when
    final Map<String, String> instanceCache = new HashMap<>();
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void attemptReconnectClearsBackoffOnSuccess() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);
    when(sessionManager.startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class)))
      .thenReturn(SessionManager.SessionResult.STARTED);

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - second call should not be in backoff
    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    verify(sessionManager, org.mockito.Mockito.times(2))
      .startSession(eq(svc), eq(awsConfig), any(), eq(pidFile), eq(false), eq(OutputMode.QUIET));
  }

  @Test
  void cleanupPreservesPidFileOnPartialKillFailure() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry1, entry2));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isProcessAlive(5678L)).thenReturn(true);

    // svc-a kills fine, svc-b fails
    when(processService.killIfExpected(1234L, 7001, "i-abc123"))
      .thenReturn(ProcessOperations.KillResult.KILLED);
    when(processService.killIfExpected(5678L, 7002, "i-def456"))
      .thenReturn(ProcessOperations.KillResult.FAILED);

    when(pidFileManager.getConfigPath(pidFile)).thenReturn(java.util.Optional.of("/test.ini"));
    when(pidFileManager.getLabel(pidFile)).thenReturn(java.util.Optional.of("test"));

    // when
    watchMode.cleanupManagedSessions(pidFile);

    // then - PID file should be rewritten with the surviving entry, not deleted
    verify(pidFileManager, never()).deletePidFile(pidFile);
    verify(pidFileManager).writeEntries(pidFile, "/test.ini", "test", List.of(entry2));
  }

  @Test
  void cleanupSkipsMismatchedPidAndPreservesSurvivor() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry1, entry2));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isProcessAlive(5678L)).thenReturn(true);

    when(processService.killIfExpected(1234L, 7001, "i-abc123"))
      .thenReturn(ProcessOperations.KillResult.KILLED);
    when(processService.killIfExpected(5678L, 7002, "i-def456"))
      .thenReturn(ProcessOperations.KillResult.SKIPPED_MISMATCH);

    when(pidFileManager.getConfigPath(pidFile)).thenReturn(java.util.Optional.of("/test.ini"));
    when(pidFileManager.getLabel(pidFile)).thenReturn(java.util.Optional.of("test"));

    // when
    watchMode.cleanupManagedSessions(pidFile);

    // then - PID file should be rewritten with the mismatched entry as survivor
    verify(pidFileManager, never()).deletePidFile(pidFile);
    verify(pidFileManager).writeEntries(pidFile, "/test.ini", "test", List.of(entry2));
  }

  @Test
  void processEntrySkipsReconnectOnPidMismatch() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(false);
    when(processService.killIfExpected(1234L, 7001, "i-abc123"))
      .thenReturn(ProcessOperations.KillResult.SKIPPED_MISMATCH);

    // when
    final Map<String, String> instanceCache = new HashMap<>();
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - should not attempt reconnect
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void processEntryReconnectsAfterKilledLimited() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(false);
    when(processService.killIfExpected(1234L, 7001, "i-abc123"))
      .thenReturn(ProcessOperations.KillResult.KILLED_LIMITED);

    when(sessionManager.startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class)))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final Map<String, String> instanceCache = new HashMap<>();
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - should still attempt reconnect after KILLED_LIMITED
    verify(sessionManager).startSession(eq(svc), eq(awsConfig), any(), eq(pidFile), eq(false), eq(OutputMode.QUIET));
  }

  @Test
  void cleanupManagedSessionsHandlesIOException() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    when(pidFileManager.readEntries(pidFile)).thenThrow(new java.io.IOException("read error"));

    // when
    watchMode.cleanupManagedSessions(pidFile);

    // then
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
    verify(pidFileManager, never()).deletePidFile(pidFile);
  }

  @Test
  void waitForPortReleaseReturnsTrueWhenPortFreedDuringPolling() {
    // given
    when(processService.isPortInUse(7001))
      .thenReturn(true)
      .thenReturn(true)
      .thenReturn(false);

    // when
    final boolean result = watchMode.waitForPortRelease(7001);

    // then
    assertTrue(result, "Should return true when port is freed during polling");
  }

  @Test
  void ensurePortFreeRecordsBackoffWhenPortStillHeldAfterKillingOrphan() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(true);
    when(processService.findPidOnPort(7001)).thenReturn(java.util.Optional.of(5678L));
    when(processService.getProcessCommand(5678L)).thenReturn(java.util.Optional.of("aws ssm start-session"));

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(processService).killProcessOnPort(7001);
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void ensurePortFreeSkipsKillWhenOrphanIsNotSsmProcess() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(true);
    when(processService.findPidOnPort(7001)).thenReturn(java.util.Optional.of(5678L));
    when(processService.getProcessCommand(5678L)).thenReturn(java.util.Optional.of("/usr/bin/node server.js"));

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then
    verify(processService, never()).killProcessOnPort(7001);
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void ensurePortFreeKillsOrphanWhenPidNotFound() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(true);
    when(processService.findPidOnPort(7001)).thenReturn(java.util.Optional.empty());

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - falls through to kill since PID can't be identified
    verify(processService).killProcessOnPort(7001);
  }

  @Test
  void ensurePortFreeKillsOrphanWhenCommandNotAvailable() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(true);
    when(processService.findPidOnPort(7001)).thenReturn(java.util.Optional.of(5678L));
    when(processService.getProcessCommand(5678L)).thenReturn(java.util.Optional.empty());

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - falls through to kill since command can't be inspected
    verify(processService).killProcessOnPort(7001);
  }

  @Test
  void waitForPortReleaseReturnsFalseOnInterrupt() throws Exception {
    // given
    when(processService.isPortInUse(7001)).thenReturn(true);

    final Thread testThread = Thread.currentThread();

    final Thread interrupter = new Thread(() -> {
      java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(100).toNanos());
      testThread.interrupt();
    });
    interrupter.start();

    // when
    final boolean result = watchMode.waitForPortRelease(7001);

    // then
    assertFalse(result, "Should return false when interrupted");
    Thread.interrupted();
    interrupter.join(1000);
  }

  @Test
  void waitForPortReleaseReturnsFalseWhenAllAttemptsExhausted() {
    // given
    when(processService.isPortInUse(7001)).thenReturn(true);

    // when
    final boolean result = watchMode.waitForPortRelease(7001);

    // then
    assertFalse(result, "Should return false when port is never freed");
    // Verify it checked more than once (polling loop ran)
    verify(processService, times(11)).isPortInUse(7001);
  }

  @Test
  void displayStatusCoversForLoopAndLastDisplayLinesGreaterThanZero() throws Exception {
    // given - both sessions alive so no reconnect/rewrite, just displayStatus called
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry1 = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "other-svc", 7002, 8080, "i-def456", 1000L);

    final ServiceConfig svc2 = new ServiceConfig("other-svc", 7002, false, 8080);
    final PortForwardConfig twoSvcConfig = new PortForwardConfig(awsConfig, List.of(svc, svc2), Path.of("/test.ini"), "test");

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry1, entry2));
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(true);
    when(processService.isProcessAlive(5678L)).thenReturn(true);
    when(processService.isPortInUse(7002)).thenReturn(true);

    final Map<String, String> instanceCache = new HashMap<>();

    // when - first call sets lastDisplayLines via displayStatus
    watchMode.checkAndReconnect(twoSvcConfig, pidFile, instanceCache);
    // second call hits the lastDisplayLines > 0 branch in displayStatus
    watchMode.checkAndReconnect(twoSvcConfig, pidFile, instanceCache);

    // then - processEntry checks alive once per entry per call (2 entries * 2 calls = 4),
    // plus displayStatus checks alive once per unique entry per call (2 entries * 2 calls = 4)
    // Total for entry1 (pid 1234): 2 (processEntry) + 2 (displayStatus) = 4
    verify(processService, times(4)).isProcessAlive(1234L);
    verify(processService, times(4)).isProcessAlive(5678L);
  }

  @Test
  void addEventEvictsOldEntriesWhenExceedingMaxLogEntries() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");

    // We trigger addEvent indirectly by causing dead sessions that attempt reconnect.
    // Each dead-session detection adds an event. We need > 5 events to trigger eviction.
    final BackoffStrategy spyBackoff = new BackoffStrategy(60);
    final WatchMode localWatch = new WatchMode(processService, pidFileManager, sessionManager, console, spyBackoff);

    // Create 7 services to generate 7 events (each dead session logs "session down, reconnecting...")
    final List<ServiceConfig> services = List.of(
      new ServiceConfig("svc-1", 7001, false, 8080),
      new ServiceConfig("svc-2", 7002, false, 8080),
      new ServiceConfig("svc-3", 7003, false, 8080),
      new ServiceConfig("svc-4", 7004, false, 8080),
      new ServiceConfig("svc-5", 7005, false, 8080),
      new ServiceConfig("svc-6", 7006, false, 8080),
      new ServiceConfig("svc-7", 7007, false, 8080)
    );
    final PortForwardConfig bigConfig = new PortForwardConfig(awsConfig, services, Path.of("/test.ini"), "test");

    final List<SessionInfo> entries = List.of(
      new SessionInfo(101L, "svc-1", 7001, 8080, "i-1", 1000L),
      new SessionInfo(102L, "svc-2", 7002, 8080, "i-2", 1000L),
      new SessionInfo(103L, "svc-3", 7003, 8080, "i-3", 1000L),
      new SessionInfo(104L, "svc-4", 7004, 8080, "i-4", 1000L),
      new SessionInfo(105L, "svc-5", 7005, 8080, "i-5", 1000L),
      new SessionInfo(106L, "svc-6", 7006, 8080, "i-6", 1000L),
      new SessionInfo(107L, "svc-7", 7007, 8080, "i-7", 1000L)
    );

    when(pidFileManager.readEntries(pidFile)).thenReturn(entries);
    // All processes dead and ports free - triggers reconnect events
    when(processService.isProcessAlive(anyLong())).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);
    when(processService.isPortInUse(7002)).thenReturn(false);
    when(processService.isPortInUse(7003)).thenReturn(false);
    when(processService.isPortInUse(7004)).thenReturn(false);
    when(processService.isPortInUse(7005)).thenReturn(false);
    when(processService.isPortInUse(7006)).thenReturn(false);
    when(processService.isPortInUse(7007)).thenReturn(false);
    when(sessionManager.startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class)))
      .thenReturn(SessionManager.SessionResult.STARTED);

    // when
    final Map<String, String> instanceCache = new HashMap<>();
    localWatch.checkAndReconnect(bigConfig, pidFile, instanceCache);

    // then - verify eventLog has at most MAX_LOG_ENTRIES (5) via the accessor method
    assertTrue(localWatch.getDisplay().eventLogSize() <= 5,
      "Event log should be capped at MAX_LOG_ENTRIES (5), but was " + localWatch.getDisplay().eventLogSize());
  }

  @Test
  void processEntrySkipsReconnectWhenBackoffInEffect() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    final BackoffStrategy backoffWithFailure = new BackoffStrategy(60);
    final long now = java.time.Instant.now().getEpochSecond();
    backoffWithFailure.recordFailure("my-svc", now);

    final WatchMode localWatch = new WatchMode(processService, pidFileManager, sessionManager, console, backoffWithFailure);

    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of(entry));
    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    localWatch.checkAndReconnect(config, pidFile, instanceCache);

    // then - should not attempt reconnect because backoff is in effect
    verify(sessionManager, never()).startSession(any(), any(), any(), any(), eq(false), any(OutputMode.class));
  }

  @Test
  void checkAndReconnectRewritesPidFileWhenSessionChanged() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    final SessionInfo entry = new SessionInfo(1234L, "unknown-svc", 7001, 8080, "i-abc123", 1000L);

    // First readEntries returns the entry with an unknown service name
    // processEntry will return true for unknown service (attemptReconnect drops it and returns true)
    // This means changed == true, triggering rewritePidFile
    when(pidFileManager.readEntries(pidFile))
      .thenReturn(List.of(entry))   // first call in checkAndReconnect
      .thenReturn(List.of());       // second call in rewritePidFile

    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);

    final Map<String, String> instanceCache = new HashMap<>();

    // when
    watchMode.checkAndReconnect(config, pidFile, instanceCache);

    // then - writeEntries should have been called (rewritePidFile path)
    verify(pidFileManager).writeEntries(eq(pidFile), any(), any(), any());
  }

  @Test
  void runLoopIsInterruptedAndCleansUp() throws Exception {
    // given
    final Path pidFile = tempDir.resolve("test.pid");
    when(pidFileManager.readEntries(pidFile)).thenReturn(List.of());

    final CountDownLatch started = new CountDownLatch(1);
    final AtomicReference<Thread> runThread = new AtomicReference<>();

    // when - start run() in a separate thread with a long interval
    final Thread thread = new Thread(() -> {
      runThread.set(Thread.currentThread());
      started.countDown();
      watchMode.run(config, pidFile, 3600);
    });
    thread.start();

    // Wait for the thread to start
    assertTrue(started.await(5, TimeUnit.SECONDS), "run() thread should have started");

    // Give it a moment to enter the sleep
    java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(200).toNanos());

    // Interrupt the run loop (simulates Ctrl+C / shutdown hook behaviour)
    thread.interrupt();
    thread.join(5000);

    // then - thread should have exited
    assertFalse(thread.isAlive(), "run() thread should have exited after interrupt");
    // cleanupManagedSessions was called (it tries to read entries and delete pid file)
    verify(pidFileManager).deletePidFile(pidFile);
  }

  @Test
  void cdiConstructorCreatesWatchModeFromConfig() {
    // given
    final Ec2pfConfig ec2pfConfig = mock(Ec2pfConfig.class);
    final Ec2pfConfig.WatchConfig watchConfig = mock(Ec2pfConfig.WatchConfig.class);
    when(ec2pfConfig.watch()).thenReturn(watchConfig);
    when(watchConfig.maxBackoffSecs()).thenReturn(120);
    when(watchConfig.portReleaseAttempts()).thenReturn(10);
    when(watchConfig.portReleaseIntervalMs()).thenReturn(500L);

    // when
    final WatchMode cdiWatchMode = new WatchMode(processService, pidFileManager, sessionManager, console, ec2pfConfig);

    // then
    assertNotNull(cdiWatchMode, "CDI constructor should create a valid WatchMode instance");
    // Verify it works by calling a method that depends on the backoff strategy
    assertDoesNotThrow(() -> cdiWatchMode.waitForPortRelease(9999));
  }
}
