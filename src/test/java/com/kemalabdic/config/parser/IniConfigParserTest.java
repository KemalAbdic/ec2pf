package com.kemalabdic.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kemalabdic.config.PortForwardConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

class IniConfigParserTest {

  private final IniConfigParser parser = new IniConfigParser();
  @TempDir
  Path tempDir;

  static Stream<Arguments> invalidConfigProvider() {
    return Stream.of(
      Arguments.of("missingRegion", """
        [aws]
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, false
        """, "region"),
      Arguments.of("missingProfile", """
        [aws]
        region = eu-west-1
        remote_port = 8080

        [services]
        svc = 7001, false
        """, "profile"),
      Arguments.of("missingRemotePort", """
        [aws]
        region = eu-west-1
        profile = my-profile

        [services]
        svc = 7001, false
        """, "remote_port"),
      Arguments.of("noServices", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080
        """, "No services"),
      Arguments.of("badPort", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 99999

        [services]
        svc = 7001, false
        """, "out of range"),
      Arguments.of("nonNumericPort", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = abc

        [services]
        svc = 7001, false
        """, "not a valid integer"),
      Arguments.of("badSkipValue", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, yes
        """, "skip must be"),
      Arguments.of("unknownSection", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [unknown]
        key = value
        """, "Unknown section"),
      Arguments.of("missingEqualsSign", """
        [aws]
        region eu-west-1
        """, "key=value"),
      Arguments.of("unknownAwsField", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080
        unknown_field = value

        [services]
        svc = 7001, false
        """, "Unknown aws field"),
      Arguments.of("keyValueOutsideSection", "key = value\n", "outside of section"),
      Arguments.of("serviceWithTooManyFields", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, false, 9090, extra
        """, "must have"),
      Arguments.of("serviceBeforeRemotePortDefined", """
        [services]
        svc = 7001, false
        """, "remote_port"),
      Arguments.of("serviceWithOnlyPort", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001
        """, "must have"),
      Arguments.of("badLocalPort", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 99999, false
        """, "out of range"),
      Arguments.of("emptyRegion", """
        [aws]
        region =
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, false
        """, "region"),
      Arguments.of("emptyProfile", """
        [aws]
        region = eu-west-1
        profile =
        remote_port = 8080

        [services]
        svc = 7001, false
        """, "profile"),
      Arguments.of("badRemotePortOverride", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, false, 99999
        """, "out of range"),
      Arguments.of("nonNumericRemotePortOverride", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, false, abc
        """, "not a valid integer"),
      Arguments.of("portZero", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 0

        [services]
        svc = 7001, false
        """, "out of range"),
      Arguments.of("nonNumericLocalPort", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = abc, false
        """, "not a valid integer"),
      Arguments.of("serviceNameWithColon", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc:bad = 7001, false
        """, "must not contain ':'"),
      Arguments.of("duplicateServiceName", """
        [aws]
        region = eu-west-1
        profile = my-profile
        remote_port = 8080

        [services]
        svc = 7001, false
        svc = 7002, false
        """, "Duplicate service name")
    );
  }

  private Path writeConfig(final String content) throws IOException {
    final Path file = tempDir.resolve("test.ini");
    Files.writeString(file, content);
    return file;
  }

  @Test
  void validConfig() throws IOException {
    // given
    final Path file = writeConfig("""
      [aws]
      region = eu-west-1
      profile = my-profile
      remote_port = 8080

      [services]
      svc-one = 7001, false
      svc-two = 7002, true
      svc-three = 7003, false, 9090
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals("eu-west-1", config.awsConfig().region());
    assertEquals("my-profile", config.awsConfig().profile());
    assertEquals(8080, config.awsConfig().remotePort());
    assertEquals(3, config.services().size());

    assertEquals("svc-one", config.services().get(0).name());
    assertEquals(7001, config.services().get(0).localPort());
    assertFalse(config.services().get(0).skip());
    assertEquals(8080, config.services().get(0).remotePort());

    assertEquals("svc-two", config.services().get(1).name());
    assertTrue(config.services().get(1).skip());

    assertEquals("svc-three", config.services().get(2).name());
    assertEquals(9090, config.services().get(2).remotePort());

    assertEquals("test", config.configLabel());
  }

  @ParameterizedTest(name = "parse error: {0}")
  @MethodSource("invalidConfigProvider")
  void invalidConfigThrowsWithExpectedMessage(final String description, final String configContent,
                                              final String expectedSubstring)
    throws IOException {
    // given
    final Path file = writeConfig(configContent);

    // when
    final ConfigParseException ex = assertThrows(ConfigParseException.class, () -> parser.parse(file));

    // then
    assertTrue(ex.getMessage().contains(expectedSubstring),
      "Expected message to contain '%s' but was: %s".formatted(expectedSubstring, ex.getMessage()));
  }

  @Test
  void inlineComments() throws IOException {
    // given
    final Path file = writeConfig("""
      [aws]
      region = eu-west-1 # inline comment
      profile = my-profile
      remote_port = 8080

      [services]
      svc = 7001, false # active service
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals("eu-west-1", config.awsConfig().region());
    assertEquals(7001, config.services().get(0).localPort());
  }

  @Test
  void remotePortOverride() throws IOException {
    // given
    final Path file = writeConfig("""
      [aws]
      region = eu-west-1
      profile = my-profile
      remote_port = 8080

      [services]
      svc-default = 7001, false
      svc-custom = 7002, false, 3000
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals(8080, config.services().get(0).remotePort());
    assertEquals(3000, config.services().get(1).remotePort());
  }

  @Test
  void stripInlineComment() {
    // given
    // when
    // then
    assertEquals("value", IniConfigParser.stripInlineComment("value # comment"));
    assertEquals("value", IniConfigParser.stripInlineComment("value"));
    assertEquals("a#b", IniConfigParser.stripInlineComment("a#b"));
    assertEquals("a#b", IniConfigParser.stripInlineComment("a#b # real comment"));
  }

  @Test
  void commentsAndBlankLines() throws IOException {
    // given
    final Path file = writeConfig("""
      # Top comment

      [aws]
      # AWS settings
      region = eu-west-1
      profile = my-profile
      remote_port = 8080

      [services]
      # Service list
      svc = 7001, false
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals(1, config.services().size());
  }

  @Test
  void testResourceConfig() {
    // given
    final Path file = Path.of("src/test/resources/test-config.ini");

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals("eu-west-1", config.awsConfig().region());
    assertEquals("test-profile", config.awsConfig().profile());
    assertEquals(8080, config.awsConfig().remotePort());
    assertEquals(3, config.services().size());
    assertEquals(9090, config.services().get(2).remotePort());
  }

  @Test
  void configFileNotReadableThrows() {
    // given
    final Path file = Path.of("nonexistent-dir/nonexistent.ini");

    // when / then
    assertThrows(ConfigParseException.class, () -> parser.parse(file));
  }

  @Test
  void configLabelRemovesExtension() throws IOException {
    // given
    final Path file = writeConfig("""
      [aws]
      region = eu-west-1
      profile = my-profile
      remote_port = 8080

      [services]
      svc = 7001, false
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals("test", config.configLabel());
  }

  @Test
  void configLabelWithoutExtensionUsesFullFilename() throws IOException {
    // given - file without extension
    final Path file = tempDir.resolve("myconfig");
    Files.writeString(file, """
      [aws]
      region = eu-west-1
      profile = my-profile
      remote_port = 8080

      [services]
      svc = 7001, false
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals("myconfig", config.configLabel());
  }

  @Test
  void sectionHeaderWithExtraSpacesIsParsed() throws IOException {
    // given
    final Path file = writeConfig("""
      [  aws  ]
      region = eu-west-1
      profile = my-profile
      remote_port = 8080

      [  services  ]
      svc = 7001, false
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals("eu-west-1", config.awsConfig().region());
    assertEquals(1, config.services().size());
  }

  @Test
  void portAtBoundaryMinIsAccepted() throws IOException {
    // given
    final Path file = writeConfig("""
      [aws]
      region = eu-west-1
      profile = my-profile
      remote_port = 1

      [services]
      svc = 1, false
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals(1, config.awsConfig().remotePort());
    assertEquals(1, config.services().get(0).localPort());
  }

  @Test
  void portAtBoundaryMaxIsAccepted() throws IOException {
    // given
    final Path file = writeConfig("""
      [aws]
      region = eu-west-1
      profile = my-profile
      remote_port = 65535

      [services]
      svc = 65535, false
      """);

    // when
    final PortForwardConfig config = parser.parse(file);

    // then
    assertEquals(65535, config.awsConfig().remotePort());
  }
}
