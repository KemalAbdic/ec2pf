package com.kemalabdic.session;

import com.kemalabdic.config.Ec2pfConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@ApplicationScoped
public class PidFileManager {

  private static final String PID_FILE_PREFIX = ".port-forwards-";
  private static final String PID_FILE_SUFFIX = ".pid";
  private static final String HEADER_CONFIG_PATH = "#config_path=";
  private static final String HEADER_CONFIG_LABEL = "#config_label=";

  private final String pidDirectory;

  @Inject
  public PidFileManager(final Ec2pfConfig config) {
    this.pidDirectory = config.pid().directory();
  }

  public PidFileManager(final String pidDirectory) {
    this.pidDirectory = pidDirectory;
  }

  static String sha256(final String input) {
    try {
      final MessageDigest md = MessageDigest.getInstance("SHA-256");
      final byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (final NoSuchAlgorithmException e) {
      throw new UnsupportedOperationException("SHA-256 not available", e);
    }
  }

  public Path pidFileFor(final String configPath) {
    final String hash = sha256(configPath);
    return Path.of(pidDirectory, "%s%s%s".formatted(PID_FILE_PREFIX, hash, PID_FILE_SUFFIX));
  }

  public Path legacyPidFile(final String label) {
    return Path.of(pidDirectory, "%s%s%s".formatted(PID_FILE_PREFIX, label, PID_FILE_SUFFIX));
  }

  public void ensureHeader(final Path pidFile, final String configPath, final String configLabel) throws IOException {
    if (Files.exists(pidFile)) {
      return;
    }
    final String header = HEADER_CONFIG_PATH + configPath + "\n" + HEADER_CONFIG_LABEL + configLabel + "\n";
    Files.writeString(pidFile, header, StandardOpenOption.CREATE_NEW);
  }

  public void appendEntry(final Path pidFile, final SessionInfo entry) throws IOException {
    Files.writeString(pidFile, entry.toLine() + "\n", StandardOpenOption.APPEND);
  }

  public List<SessionInfo> readEntries(final Path pidFile) throws IOException {
    if (!Files.exists(pidFile)) {
      return List.of();
    }
    final List<SessionInfo> entries = new ArrayList<>();
    for (final String line : Files.readAllLines(pidFile)) {
      SessionInfo.parse(line).ifPresent(entries::add);
    }
    return entries;
  }

  public void writeEntries(final Path pidFile, final String configPath, final String configLabel, final List<SessionInfo> entries)
    throws IOException {
    final StringBuilder sb = new StringBuilder();
    sb.append(HEADER_CONFIG_PATH).append(configPath).append('\n');
    sb.append(HEADER_CONFIG_LABEL).append(configLabel).append('\n');
    for (final SessionInfo entry : entries) {
      sb.append(entry.toLine()).append('\n');
    }
    Files.writeString(pidFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  public Optional<String> getConfigPath(final Path pidFile) throws IOException {
    if (!Files.exists(pidFile)) {
      return Optional.empty();
    }
    for (final String line : Files.readAllLines(pidFile)) {
      if (line.startsWith(HEADER_CONFIG_PATH)) {
        return Optional.of(line.substring(HEADER_CONFIG_PATH.length()));
      }
    }
    return Optional.empty();
  }

  public Optional<String> getLabel(final Path pidFile) throws IOException {
    if (!Files.exists(pidFile)) {
      return Optional.empty();
    }
    for (final String line : Files.readAllLines(pidFile)) {
      if (line.startsWith(HEADER_CONFIG_LABEL)) {
        return Optional.of(line.substring(HEADER_CONFIG_LABEL.length()));
      }
    }
    return Optional.empty();
  }

  public List<Path> findAllPidFiles() throws IOException {
    final Path dir = Path.of(pidDirectory);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.list(dir)) {
      return stream
        .filter(p -> {
          final String name = p.getFileName().toString();
          return name.startsWith(PID_FILE_PREFIX) && name.endsWith(PID_FILE_SUFFIX);
        })
        .toList();
    }
  }

  public void deletePidFile(final Path pidFile) throws IOException {
    Files.deleteIfExists(pidFile);
  }
}
