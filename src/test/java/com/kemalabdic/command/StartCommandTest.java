package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

@QuarkusMainTest
class StartCommandTest {

  @Test
  @Launch(value = {"start", "--help"}, exitCode = 0)
  void helpExitsWithZero(final LaunchResult result) {
    // given
    // CLI launched with "start --help"

    // when
    final String output = result.getOutput();

    // then
    assertTrue(output.contains("--config"), "Should show --config option");
    assertTrue(output.contains("--dry-run"), "Should show --dry-run option");
    assertTrue(output.contains("--watch"), "Should show --watch option");
  }

  @Test
  @Launch(value = {"start"}, exitCode = 2)
  void missingConfigExitsWithError(final LaunchResult result) {
    // given
    // CLI launched with "start" and no --config argument

    // when
    // command executes via @Launch annotation

    // then
    assertNotNull(result);
  }
}
