package com.kemalabdic.session;

import static com.kemalabdic.session.OutputMode.QUIET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kemalabdic.aws.SsmOperations;
import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class SessionManagerTest {

  private final AwsConfig awsConfig = new AwsConfig("eu-west-1", "test-profile", 8080);
  private SsmOperations ssmSessionService;
  private ProcessOperations processService;
  private PidFileManager pidFileManager;
  private SessionManager sessionManager;

  @BeforeEach
  void setUp() {
    ssmSessionService = mock(SsmOperations.class);
    processService = mock(ProcessOperations.class);
    pidFileManager = mock(PidFileManager.class);
    sessionManager = new SessionManager(ssmSessionService, processService, pidFileManager, new ConsoleOutput());
  }

  @Test
  void prefetchSkipsServicesWithSkipTrue() {
    // given
    final ServiceConfig skipped = new ServiceConfig("skip-me", 7001, true, 8080);
    final ServiceConfig active = new ServiceConfig("keep-me", 7002, false, 8080);

    when(ssmSessionService.batchLookupInstanceIds(List.of("keep-me"), awsConfig))
      .thenReturn(Map.of("keep-me", "i-abc123"));

    // when
    final Map<String, String> result = sessionManager.prefetchInstanceIds(List.of(skipped, active), awsConfig);

    // then
    assertEquals(1, result.size());
    assertEquals("i-abc123", result.get("keep-me"));
    verify(ssmSessionService).batchLookupInstanceIds(List.of("keep-me"), awsConfig);
  }

  @Test
  void prefetchEmptyListReturnsEmpty() {
    // given
    final ServiceConfig s1 = new ServiceConfig("a", 7001, true, 8080);
    final ServiceConfig s2 = new ServiceConfig("b", 7002, true, 8080);

    // when
    final Map<String, String> result = sessionManager.prefetchInstanceIds(List.of(s1, s2), awsConfig);

    // then
    assertTrue(result.isEmpty());
    verify(ssmSessionService, never()).batchLookupInstanceIds(anyList(), any());
  }

  @Test
  void startSessionReturnsSkippedWhenPortInUse(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");

    when(processService.isPortInUse(7001)).thenReturn(true);

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, new HashMap<>(), pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.SKIPPED, result);
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void startSessionReturnsSkippedWhenSkipTrue(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, true, 8080);
    final Path pidFile = tempDir.resolve("test.pid");

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, new HashMap<>(), pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.SKIPPED, result);
    verify(processService, never()).isPortInUse(anyInt());
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void startSessionReturnsFailedWhenInstanceNotFound(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.lookupInstanceId("my-service", awsConfig)).thenReturn(Optional.empty());

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, new HashMap<>(), pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.FAILED, result);
    verify(ssmSessionService).lookupInstanceId("my-service", awsConfig);
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void startSessionDryRunNeverCallsAwsStartSession(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, true);

    // then
    assertEquals(SessionManager.SessionResult.STARTED, result);
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void startSessionHappyPathAppendsPidEntry(@TempDir final Path tempDir) throws IOException {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.startSession(7001, 8080, "i-abc123", awsConfig))
      .thenReturn(Optional.of(12345L));

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.STARTED, result);
    verify(pidFileManager).appendEntry(eq(pidFile), argThat(entry ->
      entry.pid() == 12345L
        && entry.service().equals("my-service")
        && entry.localPort() == 7001
        && entry.remotePort() == 8080
        && entry.instanceId().equals("i-abc123")));
  }

  @Test
  void startSessionKillsProcessIfPidWriteFails(@TempDir final Path tempDir) throws IOException {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.startSession(7001, 8080, "i-abc123", awsConfig))
      .thenReturn(Optional.of(12345L));
    doThrow(new IOException("disk full")).when(pidFileManager).appendEntry(eq(pidFile), any(SessionInfo.class));

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.FAILED, result);
    verify(processService).killProcessTree(12345L);
  }

  @Test
  void startSessionWithQuietTrueSuppressesSkipOutput(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, true, 8080);
    final Path pidFile = tempDir.resolve("test.pid");

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, new HashMap<>(), pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.SKIPPED, result);
  }

  @Test
  void resolveInstanceIdCachesMissLookupResult(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>();

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.lookupInstanceId("my-service", awsConfig))
      .thenReturn(Optional.of("i-looked-up"));
    when(ssmSessionService.startSession(7001, 8080, "i-looked-up", awsConfig))
      .thenReturn(Optional.of(99999L));

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.STARTED, result);
    assertEquals("i-looked-up", cache.get("my-service"));
  }

  @Test
  void startSessionReturnsFailedWhenSsmReturnsEmpty(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.startSession(7001, 8080, "i-abc123", awsConfig))
      .thenReturn(Optional.empty());

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false);

    // then
    assertEquals(SessionManager.SessionResult.FAILED, result);
  }

  @Test
  void executeSessionWithQuietSuppressesConnectingAndConnectedOutput(@TempDir final Path tempDir) throws IOException {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.startSession(7001, 8080, "i-abc123", awsConfig))
      .thenReturn(Optional.of(55555L));

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.STARTED, result);
    verify(pidFileManager).appendEntry(eq(pidFile), argThat(entry ->
      entry.pid() == 55555L && entry.service().equals("my-service")));
  }

  @Test
  void executeSessionWithQuietAndSessionFailReturnsFailedSilently(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.startSession(7001, 8080, "i-abc123", awsConfig))
      .thenReturn(Optional.empty());

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.FAILED, result);
    verify(ssmSessionService).startSession(7001, 8080, "i-abc123", awsConfig);
  }

  @Test
  void startSessionWithQuietAndPortInUseReturnsSkippedSilently(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");

    when(processService.isPortInUse(7001)).thenReturn(true);

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, new HashMap<>(), pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.SKIPPED, result);
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void startSessionWithQuietAndDryRunReturnsStartedSilently(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, true, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.STARTED, result);
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void prefetchInstanceIdsReturnsEmptyWhenBatchResolvesNothing() {
    // given
    final ServiceConfig service = new ServiceConfig("active-svc", 7001, false, 8080);

    when(ssmSessionService.batchLookupInstanceIds(List.of("active-svc"), awsConfig))
      .thenReturn(Map.of());

    // when
    final Map<String, String> result = sessionManager.prefetchInstanceIds(List.of(service), awsConfig);

    // then
    assertTrue(result.isEmpty());
    verify(ssmSessionService).batchLookupInstanceIds(List.of("active-svc"), awsConfig);
  }

  @Test
  void resolveInstanceIdWithQuietSuppressesLookupOutput(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>();

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.lookupInstanceId("my-service", awsConfig))
      .thenReturn(Optional.of("i-quiet-looked"));
    when(ssmSessionService.startSession(7001, 8080, "i-quiet-looked", awsConfig))
      .thenReturn(Optional.of(77777L));

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.STARTED, result);
    assertEquals("i-quiet-looked", cache.get("my-service"));
    verify(ssmSessionService).lookupInstanceId("my-service", awsConfig);
  }

  @Test
  void resolveInstanceIdWithQuietAndNotFoundReturnsFailedSilently(@TempDir final Path tempDir) {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>();

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.lookupInstanceId("my-service", awsConfig))
      .thenReturn(Optional.empty());

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.FAILED, result);
    verify(ssmSessionService).lookupInstanceId("my-service", awsConfig);
    verify(ssmSessionService, never()).startSession(anyInt(), anyInt(), anyString(), any());
  }

  @Test
  void recordSessionWithQuietSuppressesPidWriteErrorWarning(@TempDir final Path tempDir) throws IOException {
    // given
    final ServiceConfig service = new ServiceConfig("my-service", 7001, false, 8080);
    final Path pidFile = tempDir.resolve("test.pid");
    final Map<String, String> cache = new HashMap<>(Map.of("my-service", "i-abc123"));

    when(processService.isPortInUse(7001)).thenReturn(false);
    when(ssmSessionService.startSession(7001, 8080, "i-abc123", awsConfig))
      .thenReturn(Optional.of(88888L));
    doThrow(new IOException("disk full")).when(pidFileManager).appendEntry(eq(pidFile), any(SessionInfo.class));

    // when
    final SessionManager.SessionResult result = sessionManager.startSession(
      service, awsConfig, cache, pidFile, false, QUIET);

    // then
    assertEquals(SessionManager.SessionResult.FAILED, result);
    verify(processService).killProcessTree(88888L);
  }
}
