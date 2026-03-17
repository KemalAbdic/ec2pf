package com.kemalabdic.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kemalabdic.config.Ec2pfConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

class PidFileManagerTest {

  @TempDir
  Path tempDir;
  private PidFileManager manager;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    manager = new PidFileManager(tempDir.toString());
  }

  @Test
  void roundTripReadWrite() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-test.pid");

    final List<SessionInfo> entries = List.of(
      new SessionInfo(1234, "svc-alpha", 7001, 8080, "i-abc123", 1700000000),
      new SessionInfo(5678, "svc-beta", 7002, 9090, "i-def456", 1700000001));

    // when
    manager.writeEntries(pidFile, "/path/to/config.ini", "config", entries);

    final List<SessionInfo> read = manager.readEntries(pidFile);

    // then
    assertEquals(2, read.size());
    assertEquals(1234, read.get(0).pid());
    assertEquals("svc-alpha", read.get(0).service());
    assertEquals(7001, read.get(0).localPort());
    assertEquals(8080, read.get(0).remotePort());
    assertEquals("i-abc123", read.get(0).instanceId());
    assertEquals(1700000000, read.get(0).startedEpoch());

    assertEquals(5678, read.get(1).pid());
    assertEquals("svc-beta", read.get(1).service());
  }

  @Test
  void labelRetrieval() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-test.pid");
    manager.writeEntries(pidFile, "/path/to/config.ini", "my-label", List.of());

    // when / then
    assertEquals("my-label", manager.getLabel(pidFile).orElse(null));
  }

  @Test
  void hashDeterminism() {
    // when
    final String hash1 = PidFileManager.sha256("/path/to/config.ini");
    final String hash2 = PidFileManager.sha256("/path/to/config.ini");

    // then
    assertEquals(hash1, hash2);
    assertEquals(64, hash1.length());
  }

  @Test
  void differentPathsDifferentHashes() {
    // when
    final String hash1 = PidFileManager.sha256("/path/to/config-a.ini");
    final String hash2 = PidFileManager.sha256("/path/to/config-b.ini");

    // then
    assertNotEquals(hash1, hash2);
  }

  @Test
  void appendEntry() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-test.pid");
    manager.ensureHeader(pidFile, "/path/config.ini", "label");

    final SessionInfo entry = new SessionInfo(1111, "svc", 7001, 8080, "i-111", 1700000000);

    // when
    manager.appendEntry(pidFile, entry);

    // then
    final List<SessionInfo> entries = manager.readEntries(pidFile);
    assertEquals(1, entries.size());
    assertEquals(1111, entries.get(0).pid());
  }

  @Test
  void ensureHeaderDoesNotOverwrite() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-test.pid");
    manager.ensureHeader(pidFile, "/first/path", "first-label");

    final SessionInfo entry = new SessionInfo(1111, "svc", 7001, 8080, "i-111", 1700000000);
    manager.appendEntry(pidFile, entry);

    // when - second call should not overwrite
    manager.ensureHeader(pidFile, "/second/path", "second-label");

    // then
    assertEquals("first-label", manager.getLabel(pidFile).orElse(null));
    assertEquals(1, manager.readEntries(pidFile).size());
  }

  @Test
  void readNonExistentFile() throws IOException {
    // given
    final Path pidFile = tempDir.resolve("does-not-exist.pid");

    // when
    final List<SessionInfo> entries = manager.readEntries(pidFile);

    // then
    assertTrue(entries.isEmpty());
  }

  @Test
  void deletePidFile() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-test.pid");
    Files.writeString(pidFile, "content");
    assertTrue(Files.exists(pidFile));

    // when
    manager.deletePidFile(pidFile);

    // then
    assertFalse(Files.exists(pidFile));
  }

  @Test
  void sessionInfoToLineAndParse() {
    // given
    final SessionInfo original = new SessionInfo(42, "my-svc", 7001, 8080, "i-abc", 1700000000);

    // when
    final String line = original.toLine();

    // then
    assertEquals("42:my-svc:7001:8080:i-abc:1700000000", line);

    // when
    final SessionInfo parsed = SessionInfo.parse(line).orElseThrow();

    // then
    assertEquals(original.pid(), parsed.pid());
    assertEquals(original.service(), parsed.service());
    assertEquals(original.localPort(), parsed.localPort());
    assertEquals(original.remotePort(), parsed.remotePort());
    assertEquals(original.instanceId(), parsed.instanceId());
    assertEquals(original.startedEpoch(), parsed.startedEpoch());
  }

  @Test
  void parseIgnoresCommentsAndBlanks() {
    // when / then
    assertTrue(SessionInfo.parse(null).isEmpty());
    assertTrue(SessionInfo.parse("# comment").isEmpty());
    assertTrue(SessionInfo.parse("").isEmpty());
    assertTrue(SessionInfo.parse("  ").isEmpty());
  }

  @Test
  void parseReturnsEmptyForWrongNumberOfParts() {
    // given
    final String line = "1234:svc:7001:8080";

    // when
    final java.util.Optional<SessionInfo> result = SessionInfo.parse(line);

    // then
    assertTrue(result.isEmpty(), "Should return empty for line with wrong number of parts");
  }

  @Test
  void parseHandlesCarriageReturnLineEndings() {
    // given
    final String lineWithCr = "1234:svc-alpha:7001:8080:i-abc123:1700000000\r";

    // when
    final Optional<SessionInfo> result = SessionInfo.parse(lineWithCr);

    // then
    assertTrue(result.isPresent());
    final SessionInfo info = result.get();
    assertEquals(1234, info.pid());
    assertEquals("svc-alpha", info.service());
    assertEquals(7001, info.localPort());
    assertEquals(8080, info.remotePort());
    assertEquals("i-abc123", info.instanceId());
    assertEquals(1700000000, info.startedEpoch());
  }

  @Test
  void parseReturnsEmptyForNonNumericFields() {
    // given
    final String line = "not-a-number:svc:7001:8080:i-abc:1700000000";

    // when
    final java.util.Optional<SessionInfo> result = SessionInfo.parse(line);

    // then
    assertTrue(result.isEmpty(), "Should return empty for non-numeric PID field");
  }

  @Test
  void parseWithLoggingDisabledStillReturnsEmpty() {
    // given
    final Logger logger = Logger.getLogger(SessionInfo.class.getName());
    final Level original = logger.getLevel();
    logger.setLevel(Level.OFF);
    try {
      // when / then - wrong field count
      assertTrue(SessionInfo.parse("1234:svc:7001:8080").isEmpty());
      // when / then - non-numeric field
      assertTrue(SessionInfo.parse("not-a-number:svc:7001:8080:i-abc:1700000000").isEmpty());
    } finally {
      logger.setLevel(original);
    }
  }

  @Test
  void findAllPidFilesReturnsMatchingFilesOnly() throws IOException {
    // given
    Files.writeString(tempDir.resolve(".port-forwards-abc.pid"), "data");
    Files.writeString(tempDir.resolve(".port-forwards-def.pid"), "data");
    Files.writeString(tempDir.resolve("other-file.txt"), "data");

    // when
    final List<Path> pidFiles = manager.findAllPidFiles();

    // then
    assertEquals(2, pidFiles.size(), "Should find exactly 2 PID files");
  }

  @Test
  void pidFileForReturnsPathWithCorrectPrefixAndSuffix() {
    // given
    final String configPath = "/some/path/config.ini";

    // when
    final Path result = manager.pidFileFor(configPath);

    // then
    final String fileName = result.getFileName().toString();
    assertTrue(fileName.startsWith(".port-forwards-"), "Should start with .port-forwards-");
    assertTrue(fileName.endsWith(".pid"), "Should end with .pid");
    assertEquals(tempDir, result.getParent(), "Should be in the pid directory");

    final String hash = PidFileManager.sha256(configPath);
    assertEquals(".port-forwards-" + hash + ".pid", fileName);
  }

  @Test
  void legacyPidFileReturnsPathWithLabelInName() {
    // given
    final String label = "my-config";

    // when
    final Path result = manager.legacyPidFile(label);

    // then
    final String fileName = result.getFileName().toString();
    assertEquals(".port-forwards-my-config.pid", fileName);
    assertEquals(tempDir, result.getParent(), "Should be in the pid directory");
  }

  @Test
  void cdiConstructorUsesConfigDirectory() {
    // given
    final Ec2pfConfig config = mock(Ec2pfConfig.class);
    final Ec2pfConfig.PidConfig pidConfig = mock(Ec2pfConfig.PidConfig.class);
    when(config.pid()).thenReturn(pidConfig);
    when(pidConfig.directory()).thenReturn(tempDir.toString());

    // when
    final PidFileManager cdiManager = new PidFileManager(config);
    final Path result = cdiManager.pidFileFor("/test/config.ini");

    // then
    assertEquals(tempDir, result.getParent(), "CDI-constructed manager should use config directory");
    assertTrue(result.getFileName().toString().startsWith(".port-forwards-"));
  }

  @Test
  void configPathRetrieval() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-test.pid");
    manager.writeEntries(pidFile, "/path/to/config.ini", "my-label", List.of());

    // when / then
    assertEquals("/path/to/config.ini", manager.getConfigPath(pidFile).orElse(null));
  }

  @Test
  void getConfigPathReturnsEmptyForNonExistentFile() throws IOException {
    // given
    final Path pidFile = tempDir.resolve("does-not-exist.pid");

    // when
    final Optional<String> configPath = manager.getConfigPath(pidFile);

    // then
    assertTrue(configPath.isEmpty(), "Should return empty for non-existent file");
  }

  @Test
  void getConfigPathReturnsEmptyWhenFileExistsButHasNoConfigPathHeader() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-nopath.pid");
    Files.writeString(pidFile, "#config_label=some-label\n1234:svc:7001:8080:i-abc:1700000000\n");

    // when
    final Optional<String> configPath = manager.getConfigPath(pidFile);

    // then
    assertTrue(configPath.isEmpty(), "Should return empty when no config path header exists");
  }

  @Test
  void getLabelReturnsEmptyWhenFileExistsButHasNoLabelHeader() throws IOException {
    // given
    final Path pidFile = tempDir.resolve(".port-forwards-nolabel.pid");
    Files.writeString(pidFile, "#config_path=/some/path\n1234:svc:7001:8080:i-abc:1700000000\n");

    // when
    final Optional<String> label = manager.getLabel(pidFile);

    // then
    assertTrue(label.isEmpty(), "Should return empty when no label header exists");
  }

  @Test
  void getLabelReturnsEmptyForNonExistentFile() throws IOException {
    // given
    final Path pidFile = tempDir.resolve("does-not-exist.pid");

    // when
    final Optional<String> label = manager.getLabel(pidFile);

    // then
    assertTrue(label.isEmpty(), "Should return empty for non-existent file");
  }

  @Test
  void findAllPidFilesReturnsEmptyForNonExistentDirectory() throws IOException {
    // given
    final PidFileManager nonExistentManager = new PidFileManager(tempDir.resolve("no-such-dir").toString());

    // when
    final List<Path> pidFiles = nonExistentManager.findAllPidFiles();

    // then
    assertTrue(pidFiles.isEmpty(), "Should return empty list for non-existent directory");
  }

  @Test
  void findAllPidFilesExcludesFilesWithoutCorrectPrefixOrSuffix() throws IOException {
    // given
    Files.writeString(tempDir.resolve(".port-forwards-valid.pid"), "data");
    Files.writeString(tempDir.resolve("no-prefix.pid"), "data");
    Files.writeString(tempDir.resolve(".port-forwards-no-suffix.txt"), "data");
    Files.writeString(tempDir.resolve("random-file.log"), "data");

    // when
    final List<Path> pidFiles = manager.findAllPidFiles();

    // then
    assertEquals(1, pidFiles.size(), "Should find only the file with correct prefix and suffix");
    assertEquals(".port-forwards-valid.pid", pidFiles.get(0).getFileName().toString());
  }
}
