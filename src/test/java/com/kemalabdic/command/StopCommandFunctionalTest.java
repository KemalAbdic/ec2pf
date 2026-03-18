package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.process.ProcessOperations.KillResult;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.ExitCode;

class StopCommandFunctionalTest {

  private static final String VALID_INI = """
    [aws]
    region = eu-west-1
    profile = test
    remote_port = 8080
    [services]
    svc-a = 7001, false
    """;

  @TempDir
  Path tempDir;
  private IniConfigParser configParser;
  private PidFileManager pidFileManager;
  private ProcessOperations processService;
  private StopCommand command;

  @BeforeEach
  void setUp() {
    configParser = new IniConfigParser();
    pidFileManager = new PidFileManager(tempDir.toString());
    processService = mock(ProcessOperations.class);
    command = new StopCommand(configParser, pidFileManager, processService, new ConsoleOutput());
    command.dryRun = false;
  }

  private void setStopAll() {
    final StopCommand.StopTarget target = new StopCommand.StopTarget();
    target.all = true;
    command.target = target;
  }

  private void setStopByConfig(final Path configFile) {
    final StopCommand.StopTarget target = new StopCommand.StopTarget();
    target.configFile = configFile;
    command.target = target;
  }

  private Path writeConfigFile(final String name, final String content) throws IOException {
    final Path configFile = tempDir.resolve(name);
    Files.writeString(configFile, content);
    return configFile;
  }

  private void writePidFile(final Path pidFile, final String configPath, final String configLabel,
                            final SessionInfo... entries) throws IOException {
    pidFileManager.ensureHeader(pidFile, configPath, configLabel);
    for (final SessionInfo entry : entries) {
      pidFileManager.appendEntry(pidFile, entry);
    }
  }

  @Test
  void callReturnsSoftwareWhenTargetIsNull() {
    // given
    command.target = null;

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void stopAllWithNoPidFilesReturnsOk() {
    // given - no PID files in tempDir
    setStopAll();

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
  }

  @Test
  void stopAllKillsAllSessionsAndDeletesPidFiles() throws IOException {
    // given
    setStopAll();

    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456", 1000L);

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    writePidFile(pidFile, "some-config-path", "test", entry1, entry2);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.KILLED);
    when(processService.killIfExpected(5678L, 7002, "i-def456")).thenReturn(KillResult.KILLED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService).killIfExpected(1234L, 7001, "i-abc123");
    verify(processService).killIfExpected(5678L, 7002, "i-def456");
    assertFalse(Files.exists(pidFile));
  }

  @Test
  void stopByConfigKillsCorrectProcesses() throws IOException {
    // given
    final Path configFile = writeConfigFile("config.ini", VALID_INI);
    setStopByConfig(configFile);

    final String absoluteConfigPath = configFile.toAbsolutePath().normalize().toString();
    final Path pidFile = pidFileManager.pidFileFor(absoluteConfigPath);

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    writePidFile(pidFile, absoluteConfigPath, "config", entry);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.KILLED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService).killIfExpected(1234L, 7001, "i-abc123");
    assertFalse(Files.exists(pidFile));
  }

  @Test
  void stopWithFailedKillReturnsNonZero() throws IOException {
    // given
    setStopAll();

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    writePidFile(pidFile, "some-config-path", "test", entry);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.FAILED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void dryRunDoesNotKillOrDeletePidFile() throws IOException {
    // given
    command.dryRun = true;
    setStopAll();

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    writePidFile(pidFile, "some-config-path", "test", entry);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
    assertTrue(Files.exists(pidFile));
  }

  @Test
  void stopByConfigWithMissingConfigReturnsError() {
    // given
    final Path nonexistent = tempDir.resolve("missing.ini");
    setStopByConfig(nonexistent);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void stopWithMixedResultsReturnsNonZeroWhenAnyFailed() throws IOException {
    // given
    setStopAll();

    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456", 1000L);

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    writePidFile(pidFile, "some-config-path", "test", entry1, entry2);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.KILLED);
    when(processService.killIfExpected(5678L, 7002, "i-def456")).thenReturn(KillResult.FAILED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void skippedMismatchDoesNotCountAsFailure() throws IOException {
    // given
    setStopAll();

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    writePidFile(pidFile, "some-config-path", "test", entry);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.SKIPPED_MISMATCH);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void stopByConfigIncludesLegacyPidFile() throws IOException {
    // given
    final Path configFile = writeConfigFile("config.ini", VALID_INI);
    setStopByConfig(configFile);

    final String absoluteConfigPath = configFile.toAbsolutePath().normalize().toString();
    final Path hashPidFile = pidFileManager.pidFileFor(absoluteConfigPath);
    final Path legacyPidFile = pidFileManager.legacyPidFile("config");

    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456", 1000L);

    writePidFile(hashPidFile, absoluteConfigPath, "config", entry1);
    writePidFile(legacyPidFile, absoluteConfigPath, "config", entry2);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.KILLED);
    when(processService.killIfExpected(5678L, 7002, "i-def456")).thenReturn(KillResult.KILLED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService).killIfExpected(1234L, 7001, "i-abc123");
    verify(processService).killIfExpected(5678L, 7002, "i-def456");
  }

  @Test
  void stopByConfigReturnsSoftwareOnParseError() throws IOException {
    // given - write invalid INI content so real parser throws ConfigParseException
    final Path configFile = writeConfigFile("bad.ini", "invalid content");
    setStopByConfig(configFile);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void stopEntryWithKilledLimitedResultReturnsOk() throws IOException {
    // given
    setStopAll();

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    writePidFile(pidFile, "some-config-path", "test", entry);

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.KILLED_LIMITED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void stopByConfigWithNoPidFilesReturnsOk() throws IOException {
    // given - valid config file but no PID files on disk
    final Path configFile = writeConfigFile("config.ini", VALID_INI);
    setStopByConfig(configFile);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
  }

  @Test
  void stopFromPidFileUsesFileNameWhenGetLabelReturnsEmpty() throws IOException {
    // given - write a PID file with entries but no header, so getLabel returns Optional.empty()
    // and the command falls back to the file name
    setStopAll();

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123", 1000L);
    final Path pidFile = pidFileManager.pidFileFor("some-config-path");

    // Write entry line directly without header so getLabel returns empty
    Files.writeString(pidFile, entry.toLine() + "\n");

    when(processService.killIfExpected(1234L, 7001, "i-abc123")).thenReturn(KillResult.KILLED);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService).killIfExpected(1234L, 7001, "i-abc123");
    assertFalse(Files.exists(pidFile));
  }

  @Test
  void stopFromPidFileWithEmptyEntriesDeletesPidFile() throws IOException {
    // given - PID file with header only, no entries
    setStopAll();

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    pidFileManager.ensureHeader(pidFile, "some-config-path", "test");

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
    assertFalse(Files.exists(pidFile));
  }

  @Test
  void stopFromPidFileReturnsWhenReadEntriesEncountersCorruptFile() throws IOException {
    // given - PID file that is actually a directory, causing IOException on read
    setStopAll();

    final Path pidFile = pidFileManager.pidFileFor("some-config-path");
    Files.createDirectories(pidFile);

    // when
    final int exitCode = command.call();

    // then - the command should handle the I/O failure gracefully
    assertEquals(ExitCode.OK, exitCode);
    verify(processService, never()).killIfExpected(anyLong(), anyInt(), anyString());
  }
}
