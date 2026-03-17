package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.config.parser.ConfigParseException;
import com.kemalabdic.config.parser.IniConfigParser;
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
import java.util.List;
import picocli.CommandLine.ExitCode;

class ValidateCommandTest {

  @TempDir
  Path tempDir;
  private IniConfigParser configParser;
  private ValidateCommand command;
  private PrintStream originalOut;
  private ByteArrayOutputStream capturedOut;

  @BeforeEach
  void setUp() {
    configParser = mock(IniConfigParser.class);
    originalOut = System.out;
    capturedOut = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capturedOut));
    command = new ValidateCommand(configParser, new ConsoleOutput(new PrintStream(capturedOut), false));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  private String output() {
    return capturedOut.toString();
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
    // given
    final Path configFile = tempDir.resolve("bad.ini");
    Files.writeString(configFile, "invalid");
    command.configFile = configFile;
    when(configParser.parse(configFile)).thenThrow(new ConfigParseException("bad format"));

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.SOFTWARE, exitCode);
  }

  @Test
  void callReturnsOkForValidConfig() throws IOException {
    // given
    final Path configFile = tempDir.resolve("valid.ini");
    Files.writeString(configFile, "[aws]\nregion=eu-west-1\n");
    command.configFile = configFile;

    final AwsConfig awsConfig = new AwsConfig("eu-west-1", "my-profile", 8080);
    final List<ServiceConfig> services = List.of(new ServiceConfig("svc-a", 7001, false, 8080));
    final PortForwardConfig config = new PortForwardConfig(awsConfig, services, configFile, "valid");
    when(configParser.parse(configFile)).thenReturn(config);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
  }

  @Test
  void callDisplaysPortOverrideWhenDifferentFromDefault() throws IOException {
    // given
    final Path configFile = tempDir.resolve("override.ini");
    Files.writeString(configFile, "[aws]\nregion=eu-west-1\n");
    command.configFile = configFile;

    final AwsConfig awsConfig = new AwsConfig("eu-west-1", "my-profile", 8080);
    final List<ServiceConfig> services = List.of(new ServiceConfig("svc-custom", 7001, false, 9090));
    final PortForwardConfig config = new PortForwardConfig(awsConfig, services, configFile, "override");
    when(configParser.parse(configFile)).thenReturn(config);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("9090"), "Should display port override, got: " + out);
  }

  @Test
  void callDisplaysSkipForSkippedService() throws IOException {
    // given
    final Path configFile = tempDir.resolve("skip.ini");
    Files.writeString(configFile, "[aws]\nregion=eu-west-1\n");
    command.configFile = configFile;

    final AwsConfig awsConfig = new AwsConfig("eu-west-1", "my-profile", 8080);
    final List<ServiceConfig> services = List.of(new ServiceConfig("svc-skip", 7001, true, 8080));
    final PortForwardConfig config = new PortForwardConfig(awsConfig, services, configFile, "skip");
    when(configParser.parse(configFile)).thenReturn(config);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("svc-skip"), "Should display skipped service name, got: " + out);
  }

  @Test
  void callDisplaysInfoForActiveServiceWithSamePort() throws IOException {
    // given
    final Path configFile = tempDir.resolve("active.ini");
    Files.writeString(configFile, "[aws]\nregion=eu-west-1\n");
    command.configFile = configFile;

    final AwsConfig awsConfig = new AwsConfig("eu-west-1", "my-profile", 8080);
    final List<ServiceConfig> services = List.of(new ServiceConfig("svc-active", 7001, false, 8080));
    final PortForwardConfig config = new PortForwardConfig(awsConfig, services, configFile, "active");
    when(configParser.parse(configFile)).thenReturn(config);

    // when
    final int exitCode = command.call();

    // then
    assertEquals(ExitCode.OK, exitCode);
    final String out = output();
    assertTrue(out.contains("svc-active"), "Should display active service name, got: " + out);
    assertTrue(out.contains("7001"), "Should display local port, got: " + out);
  }

}
