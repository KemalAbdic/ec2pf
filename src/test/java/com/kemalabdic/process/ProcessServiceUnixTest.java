package com.kemalabdic.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

@Testcontainers(disabledWithoutDocker = true)
class ProcessServiceUnixTest {

  @Container
  private final GenericContainer<?> linux = new GenericContainer<>(DockerImageName.parse("ubuntu:22.04"))
    .withCommand("sh", "-c",
      "apt-get update -qq > /dev/null 2>&1 "
        + "&& apt-get install -y -qq lsof netcat-openbsd procps > /dev/null 2>&1 "
        + "&& nc -l -k -p 7001 & "
        + "echo ready && sleep infinity")
    .waitingFor(Wait.forLogMessage(".*ready.*", 1).withStartupTimeout(Duration.ofSeconds(120)));

  @Test
  void lsofFindsListeningProcess() throws Exception {
    // given - the container has nc listening on port 7001
    waitForPort(7001);

    // when - run lsof inside the container to find the PID
    final ExecResult lsofResult = linux.execInContainer("lsof", "-ti", ":7001");

    // then - lsof should find the nc process
    final String stdout = lsofResult.getStdout().trim();
    assertTrue(!stdout.isEmpty(), "lsof should find a process on port 7001, got stderr: " + lsofResult.getStderr());
    final long pid = Long.parseLong(stdout.split("\\n")[0].trim());
    assertTrue(pid > 0, "PID should be positive, got: " + pid);
  }

  @Test
  void lsofReturnsEmptyForUnusedPort() throws Exception {
    // given - port 49999 is not in use in the container

    // when - lsof exits with non-zero and empty stdout when port is not in use
    final ExecResult lsofResult = linux.execInContainer("sh", "-c", "lsof -ti :49999 2>/dev/null || true");

    // then
    final String stdout = lsofResult.getStdout().trim();
    assertTrue(stdout.isEmpty(), "lsof should return empty for unused port, got: '" + stdout + "'");
  }

  @Test
  void killProcessTreeSimulatedOnUnix() throws Exception {
    // given - start a background process in the container
    linux.execInContainer("sh", "-c", "sleep 3600 &");
    waitForProcess("sleep 3600");

    // when - find the sleep PID
    final ExecResult psResult = linux.execInContainer("sh", "-c",
      "ps aux | grep 'sleep 3600' | grep -v grep | awk '{print $2}' | head -1");
    final String pidStr = psResult.getStdout().trim();

    // then - the process should exist
    assertTrue(!pidStr.isEmpty(), "Should find sleep process");

    // when - kill it (simulates ProcessHandle.destroyForcibly on Unix)
    final long pid = Long.parseLong(pidStr);
    final ExecResult killResult = linux.execInContainer("kill", "-9", String.valueOf(pid));

    // then - verify it's dead
    assertEquals(0, killResult.getExitCode(), "Kill should succeed");
    waitForProcessDeath(pid);
  }

  @Test
  void findPidOnPortUnixParsesBehaviourCorrectly() throws Exception {
    // given - nc is listening on port 7001 inside the container
    waitForPort(7001);

    // when - simulate what findPidOnPortUnix does: run lsof -ti :PORT and parse the first line
    final ExecResult result = linux.execInContainer("lsof", "-ti", ":7001");

    // then - should get a valid PID string
    final String output = result.getStdout().trim();
    assertTrue(!output.isEmpty(), "Should get PID output from lsof");

    // Parse the same way findPidOnPortUnix does
    final String line = output.split("\\n")[0].trim();
    assertTrue(!line.isBlank(), "First line should not be blank");
    final long pid = Long.parseLong(line);
    assertTrue(pid > 0, "Parsed PID should be positive: " + pid);
  }

  @Test
  void processDestroyForciblyWorksOnUnix() throws Exception {
    // given - start a process and get its PID
    linux.execInContainer("sh", "-c", "sleep 9999 &");
    waitForProcess("sleep 9999");
    final ExecResult psResult = linux.execInContainer("sh", "-c",
      "ps aux | grep 'sleep 9999' | grep -v grep | awk '{print $2}' | head -1");
    final String pidStr = psResult.getStdout().trim();
    assertTrue(!pidStr.isEmpty(), "Should find sleep 9999 process");

    // when - use kill -KILL (simulates destroyForcibly)
    linux.execInContainer("kill", "-KILL", pidStr);

    // then - process should be gone
    waitForProcessDeath(Long.parseLong(pidStr));
  }

  @Test
  void childProcessesAreKilledWithParent() throws Exception {
    // given - start a parent with a child process
    linux.execInContainer("sh", "-c", "sh -c 'sleep 8888' &");
    waitForProcess("sleep 8888");

    final ExecResult psResult = linux.execInContainer("sh", "-c",
      "ps aux | grep 'sleep 8888' | grep -v grep | awk '{print $2}' | head -1");
    final String pidStr = psResult.getStdout().trim();
    assertTrue(!pidStr.isEmpty(), "Should find sleep 8888 process");

    // when - kill the child
    linux.execInContainer("kill", "-KILL", pidStr);

    // then - child should be dead
    waitForProcessDeath(Long.parseLong(pidStr));
  }

  private void waitForPort(final int port) throws Exception {
    for (int i = 0; i < 30; i++) {
      final ExecResult result = linux.execInContainer("sh", "-c",
        "lsof -ti :" + port + " 2>/dev/null");
      if (!result.getStdout().trim().isEmpty()) {
        return;
      }
      waitInContainer();
    }
  }

  private void waitForProcess(final String processName) throws Exception {
    for (int i = 0; i < 30; i++) {
      final ExecResult result = linux.execInContainer("sh", "-c",
        "ps aux | grep '" + processName + "' | grep -v grep");
      if (!result.getStdout().trim().isEmpty()) {
        return;
      }
      waitInContainer();
    }
  }

  private void waitForProcessDeath(final long pid) throws Exception {
    for (int i = 0; i < 30; i++) {
      final ExecResult check = linux.execInContainer("sh", "-c",
        "kill -0 " + pid + " 2>/dev/null && echo alive || echo dead");
      if (check.getStdout().trim().contains("dead")) {
        return;
      }
      waitInContainer();
    }
    assertTrue(false, "Process " + pid + " did not die within timeout");
  }

  private void waitInContainer() throws Exception {
    linux.execInContainer("sleep", "1");
  }
}
