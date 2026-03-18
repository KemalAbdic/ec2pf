package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
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
import java.time.Instant;
import picocli.CommandLine.ExitCode;

class StatusCommandTest {

  private static final String VALID_CONFIG = """
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
  private StatusCommand command;
  private PrintStream originalOut;
  private ByteArrayOutputStream capturedOut;

  @BeforeEach
  void setUp() {
    configParser = new IniConfigParser();
    pidFileManager = new PidFileManager(tempDir.toString());
    processService = mock(ProcessOperations.class);
    originalOut = System.out;
    capturedOut = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));
    command = new StatusCommand(configParser, pidFileManager, processService,
      new ConsoleOutput(new PrintStream(capturedOut), false));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  private String output() {
    return capturedOut.toString();
  }

  private void setShowAll() {
    final StatusCommand.StatusTarget target = new StatusCommand.StatusTarget();
    target.all = true;
    command.target = target;
  }

  private void setShowByConfig(final Path configFile) {
    final StatusCommand.StatusTarget target = new StatusCommand.StatusTarget();
    target.configFile = configFile;
    command.target = target;
  }

  private Path writeConfig(final String filename, final String content) throws IOException {
    final Path configFile = tempDir.resolve(filename);
    Files.writeString(configFile, content);
    return configFile;
  }

  private void writePidFile(final Path configFile, final String configLabel, final SessionInfo... entries)
    throws IOException {
    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    pidFileManager.ensureHeader(pidFile, configFile.toString(), configLabel);
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
    assertTrue(output().contains("No target specified"), "Should show null target error, got: " + output());
  }

  @Test
  void callReturnsOkWhenShowAllFindsNoFiles() {
    // given - no PID files in tempDir
    setShowAll();

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void callReturnsOkWhenShowByConfigFindsNoActiveSessions() throws IOException {
    // given - config exists but no PID file written
    final Path configFile = writeConfig("config.ini", VALID_CONFIG);
    setShowByConfig(configFile);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("No active sessions"), "Should indicate no active sessions, got: " + out);
  }

  @Test
  void callReturnsSoftwareWhenConfigFileNotFound() {
    // given
    final Path nonexistent = tempDir.resolve("missing.ini");
    setShowByConfig(nonexistent);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsSoftwareWhenConfigParseError() throws IOException {
    // given - file with invalid INI content triggers real parser error
    final Path configFile = writeConfig("bad.ini", "invalid content without sections");
    setShowByConfig(configFile);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsOkWhenDisplayPidFileSucceeds() throws IOException {
    // given
    setShowAll();

    final Path configFile = writeConfig("test.ini", VALID_CONFIG);
    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123",
      Instant.now().getEpochSecond() - 120);
    writePidFile(configFile, "test", entry);

    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(true);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("svc-a"), "Should display service name, got: " + out);
  }

  @Test
  void showAllIteratesMultiplePidFiles() throws IOException {
    // given
    setShowAll();

    final Path configFileA = writeConfig("config-a.ini", VALID_CONFIG);
    final Path configFileB = writeConfig("config-b.ini", VALID_CONFIG);

    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def", 1000L);

    writePidFile(configFileA, "config-a", entry1);
    writePidFile(configFileB, "config-b", entry2);

    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isProcessAlive(5678L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(true);
    when(processService.isPortInUse(7002)).thenReturn(false);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("svc-a"), "Should display first service, got: " + out);
    assertTrue(out.contains("svc-b"), "Should display second service, got: " + out);
  }

  @Test
  void displayPidFileFallsBackToFileNameWhenGetLabelReturnsEmpty() throws IOException {
    // given - write a PID file without the #config_label= header
    setShowAll();

    final Path configFile = writeConfig("nolabel.ini", VALID_CONFIG);
    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());

    final SessionInfo entry = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def456",
      Instant.now().getEpochSecond() - 300);

    // Manually write entry without header so getLabel returns Optional.empty()
    Files.writeString(pidFile, entry.toLine() + "\n");

    when(processService.isProcessAlive(5678L)).thenReturn(true);
    when(processService.isPortInUse(7002)).thenReturn(true);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains(".port-forwards-"),
      "Should fallback to file name when label is empty, got: " + out);
    assertTrue(out.contains("svc-b"), "Should still display service entries, got: " + out);
  }

  @Test
  void displayPidFileHandlesEmptyEntries() throws IOException {
    // given - PID file with header but no entries
    setShowAll();

    final Path configFile = writeConfig("empty.ini", VALID_CONFIG);
    final Path pidFile = pidFileManager.pidFileFor(configFile.toString());
    pidFileManager.ensureHeader(pidFile, configFile.toString(), "empty");

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("No session entries"), "Should indicate empty entries, got: " + out);
  }

  @Test
  void displayPidFileShowsUpStatusForAliveProcess() throws IOException {
    // given
    setShowAll();

    final Path configFile = writeConfig("up.ini", VALID_CONFIG);
    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123",
      Instant.now().getEpochSecond() - 60);
    writePidFile(configFile, "up", entry);

    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(true);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("up"), "Should show 'up' status, got: " + out);
  }

  @Test
  void displayPidFileShowsDownStatusForDeadProcess() throws IOException {
    // given
    setShowAll();

    final Path configFile = writeConfig("down.ini", VALID_CONFIG);
    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123",
      Instant.now().getEpochSecond() - 60);
    writePidFile(configFile, "down", entry);

    when(processService.isProcessAlive(1234L)).thenReturn(false);
    when(processService.isPortInUse(7001)).thenReturn(false);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("down"), "Should show 'down' status, got: " + out);
  }

  @Test
  void formatUptimeReturnsZeroMinutesForNegativeSeconds() {
    // given
    final long futureEpoch = Instant.now().getEpochSecond() + 1000;
    final SessionInfo entry = new SessionInfo(1L, "svc", 7001, 8080, "i-abc", futureEpoch);

    // when
    final String result = entry.formatUptime();

    // then
    assertEquals("0m", result);
  }

  @Test
  void formatUptimeReturnsDaysAndHoursForLargeUptime() {
    // given
    final long epoch = Instant.now().getEpochSecond() - (2 * 86400 + 3 * 3600);
    final SessionInfo entry = new SessionInfo(1L, "svc", 7001, 8080, "i-abc", epoch);

    // when
    final String result = entry.formatUptime();

    // then
    assertTrue(result.contains("d"), "Should contain days, got: " + result);
    assertTrue(result.contains("h"), "Should contain hours, got: " + result);
  }

  @Test
  void formatUptimeReturnsHoursAndMinutesForMediumUptime() {
    // given
    final long epoch = Instant.now().getEpochSecond() - (3 * 3600 + 15 * 60);
    final SessionInfo entry = new SessionInfo(1L, "svc", 7001, 8080, "i-abc", epoch);

    // when
    final String result = entry.formatUptime();

    // then
    assertTrue(result.contains("h"), "Should contain hours, got: " + result);
    assertTrue(result.contains("m"), "Should contain minutes, got: " + result);
  }

  @Test
  void formatUptimeReturnsMinutesOnlyForSmallUptime() {
    // given
    final long epoch = Instant.now().getEpochSecond() - (15 * 60);
    final SessionInfo entry = new SessionInfo(1L, "svc", 7001, 8080, "i-abc", epoch);

    // when
    final String result = entry.formatUptime();

    // then
    assertTrue(result.contains("m"), "Should contain minutes, got: " + result);
    assertTrue(result.equals("15m") || result.contains("m"), "Should be minutes only, got: " + result);
  }

  @Test
  void callReturnsOkWhenShowByConfigDisplaysEntries() throws IOException {
    // given - real config file and real PID file
    final Path configFile = writeConfig("config.ini", VALID_CONFIG);
    setShowByConfig(configFile);

    final SessionInfo entry = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc123",
      Instant.now().getEpochSecond() - 60);
    writePidFile(configFile, "config", entry);

    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(true);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("svc-a"), "Should display service name, got: " + out);
  }
}
