package com.kemalabdic.config.parser;

import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@ApplicationScoped
public class IniConfigParser {

  static String stripInlineComment(final String line) {
    final int idx = line.indexOf(" #");
    return idx >= 0 ? line.substring(0, idx) : line;
  }

  public PortForwardConfig parse(final Path configFile) {
    final List<String> lines = readLines(configFile);
    final ParseState state = new ParseState();

    for (int i = 0; i < lines.size(); i++) {
      parseLine(lines.get(i), i + 1, state);
    }

    return buildConfig(state, configFile);
  }

  private List<String> readLines(final Path configFile) {
    try {
      return Files.readAllLines(configFile);
    } catch (final IOException e) {
      throw new ConfigParseException("Cannot read config file: %s".formatted(configFile));
    }
  }

  private void parseLine(final String raw, final int lineNum, final ParseState state) {
    final String line = stripInlineComment(raw).trim();

    if (line.isEmpty() || line.startsWith("#")) {
      return;
    }

    if (line.startsWith("[") && line.endsWith("]")) {
      state.currentSection = line.substring(1, line.length() - 1).trim().toLowerCase();
      if (!state.currentSection.equals("aws") && !state.currentSection.equals("services")) {
        throw new ConfigParseException("Unknown section [%s] at line %d".formatted(state.currentSection, lineNum));
      }
      return;
    }

    if (Objects.isNull(state.currentSection)) {
      throw new ConfigParseException("Key/value outside of section at line %d".formatted(lineNum));
    }

    parseEntry(line, lineNum, state);
  }

  private void parseEntry(final String line, final int lineNum, final ParseState state) {
    final int eqIndex = line.indexOf('=');
    if (eqIndex < 0) {
      throw new ConfigParseException("Expected key=value at line %d: %s".formatted(lineNum, line));
    }

    final String key = line.substring(0, eqIndex).trim();
    final String value = line.substring(eqIndex + 1).trim();

    if (state.currentSection.equals("aws")) {
      parseAwsEntry(key, value, lineNum, state);
    } else if (state.currentSection.equals("services")) {
      state.services.add(parseService(key, value, lineNum, state));
    }
  }

  private void parseAwsEntry(final String key, final String value, final int lineNum, final ParseState state) {
    switch (key) {
      case "region" -> state.region = value;
      case "profile" -> state.profile = value;
      case "remote_port" -> state.defaultRemotePort = parsePort(value, "remote_port", lineNum);
      default -> throw new ConfigParseException(
        "Unknown aws field '%s' at line %d".formatted(key, lineNum));
    }
  }

  private PortForwardConfig buildConfig(final ParseState state, final Path configFile) {
    if (Objects.isNull(state.region) || state.region.isEmpty()) {
      throw new ConfigParseException("Missing required field: aws.region");
    }
    if (Objects.isNull(state.profile) || state.profile.isEmpty()) {
      throw new ConfigParseException("Missing required field: aws.profile");
    }
    if (Objects.isNull(state.defaultRemotePort)) {
      throw new ConfigParseException("Missing required field: aws.remote_port");
    }
    if (state.services.isEmpty()) {
      throw new ConfigParseException("No services defined");
    }

    final AwsConfig awsConfig = new AwsConfig(state.region, state.profile, state.defaultRemotePort);
    final String fileName = configFile.getFileName().toString();
    final String label = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

    return new PortForwardConfig(awsConfig, List.copyOf(state.services), configFile.toAbsolutePath().normalize(), label);
  }

  private ServiceConfig parseService(final String name, final String value, final int lineNum, final ParseState state) {
    if (state.serviceNames.contains(name)) {
      throw new ConfigParseException(
        "Duplicate service name '%s' at line %d".formatted(name, lineNum));
    }
    state.serviceNames.add(name);
    if (name.contains(":")) {
      throw new ConfigParseException(
        "Service name '%s' at line %d must not contain ':'".formatted(name, lineNum));
    }
    final String[] parts = value.split(",");
    if (parts.length < 2 || parts.length > 3) {
      throw new ConfigParseException(
        "Service '%s' at line %d must have format: local_port, skip [, remote_port]".formatted(name, lineNum));
    }

    final int localPort = parsePort(parts[0].trim(), "%s.local_port".formatted(name), lineNum);

    final String skipStr = parts[1].trim();
    if (!skipStr.equals("true") && !skipStr.equals("false")) {
      throw new ConfigParseException(
        "Service '%s' at line %d: skip must be 'true' or 'false', got '%s'".formatted(name, lineNum, skipStr));
    }
    final boolean skip = Boolean.parseBoolean(skipStr);

    int remotePort;
    if (parts.length == 3) {
      remotePort = parsePort(parts[2].trim(), "%s.remote_port".formatted(name), lineNum);
    } else {
      if (Objects.isNull(state.defaultRemotePort)) {
        throw new ConfigParseException(
          "Service '%s' at line %d has no remote_port override and aws.remote_port not yet defined".formatted(name, lineNum));
      }
      remotePort = state.defaultRemotePort;
    }

    return new ServiceConfig(name, localPort, skip, remotePort);
  }

  private int parsePort(final String value, final String fieldName, final int lineNum) {
    try {
      final int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new ConfigParseException(
          "Port %s at line %d out of range: %d".formatted(fieldName, lineNum, port));
      }
      return port;
    } catch (final NumberFormatException e) {
      throw new ConfigParseException(
        "Port %s at line %d is not a valid integer: %s".formatted(fieldName, lineNum, value));
    }
  }

  private static class ParseState {
    final List<ServiceConfig> services = new ArrayList<>();
    final Set<String> serviceNames = new HashSet<>();
    @Nullable String region;
    @Nullable String profile;
    @Nullable Integer defaultRemotePort;
    @Nullable String currentSection;
  }
}
