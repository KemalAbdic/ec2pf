package com.kemalabdic.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

@QuarkusMainTest
class StopCommandTest {

  @Test
  @Launch(value = {"stop", "--help"}, exitCode = 0)
  void helpExitsWithZero(final LaunchResult result) {
    // given
    // CLI launched with "stop --help"

    // when
    final String output = result.getOutput();

    // then
    assertTrue(output.contains("--config"), "Should show --config option");
    assertTrue(output.contains("--all"), "Should show --all option");
  }

  @Test
  @Launch(value = {"stop"}, exitCode = 2)
  void missingTargetExitsWithError(final LaunchResult result) {
    // given
    // CLI launched with "stop" and no target argument

    // when
    // command executes via @Launch annotation

    // then
    assertNotNull(result);
  }
}
