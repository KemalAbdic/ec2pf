package com.kemalabdic.process;

import com.kemalabdic.aws.SsmConstants;
import com.kemalabdic.util.PlatformUtils;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ProcessService implements ProcessOperations {

  private static final Logger LOG = Logger.getLogger(ProcessService.class);

  static Optional<Long> parseNetstatLine(final String line, final int port, final Pattern portPattern) {
    if (!line.contains("LISTENING")) {
      return Optional.empty();
    }
    final String[] parts = line.trim().split("\\s+");
    if (parts.length < 5) {
      return Optional.empty();
    }
    final Matcher matcher = portPattern.matcher(parts[1] + " ");
    if (matcher.find() && Integer.parseInt(matcher.group(1)) == port) {
      return Optional.of(Long.parseLong(parts[parts.length - 1]));
    }
    return Optional.empty();
  }

  @Override
  public boolean isPortInUse(final int port) {
    try (ServerSocket ss = new ServerSocket(port)) {
      ss.setReuseAddress(true);
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  boolean isWindows() {
    return PlatformUtils.isWindows();
  }

  @Override
  public boolean isProcessAlive(final long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  @Override
  public Optional<String> getProcessCommand(final long pid) {
    return ProcessHandle.of(pid)
      .flatMap(ph -> ph.info().commandLine());
  }

  boolean isExpectedSessionPid(final long pid, final int localPort, @Nullable final String instanceId) {
    final Optional<String> cmdOpt = getProcessCommand(pid);
    if (cmdOpt.isEmpty()) {
      return isProcessAlive(pid) && isPortInUse(localPort);
    }
    final String cmd = cmdOpt.get();
    if (!cmd.contains(SsmConstants.CMD_AWS) || !cmd.contains(SsmConstants.CMD_SSM)
      || !cmd.contains(SsmConstants.CMD_START_SESSION)) {
      return false;
    }
    if (!cmd.contains(SsmConstants.DOCUMENT_PORT_FORWARDING)) {
      return false;
    }
    if (!cmd.contains(SsmConstants.localPortParam(localPort))) {
      return false;
    }
    return Objects.isNull(instanceId) || instanceId.isEmpty() || cmd.contains(instanceId);
  }

  @Override
  public KillResult killIfExpected(final long pid, final int localPort, final String instanceId) {
    if (!isProcessAlive(pid)) {
      return KillResult.SKIPPED_MISMATCH;
    }

    final Optional<String> cmdOpt = getProcessCommand(pid);
    boolean validated;
    if (cmdOpt.isPresent()) {
      if (!isExpectedSessionPid(pid, localPort, instanceId)) {
        return KillResult.SKIPPED_MISMATCH;
      }
      validated = true;
    } else {
      if (!isPortInUse(localPort)) {
        return KillResult.SKIPPED_MISMATCH;
      }
      validated = false;
    }

    final boolean killed = killProcessTree(pid);
    if (!killed) {
      return KillResult.FAILED;
    }
    return validated ? KillResult.KILLED : KillResult.KILLED_LIMITED;
  }

  @Override
  public boolean killProcessTree(final long pid) {
    if (isWindows()) {
      try {
        final Process p = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
          .redirectErrorStream(true).start();
        final int exit = p.waitFor();
        if (exit != 0) {
          LOG.debugf("Failed to kill PID %d: exit=%d", pid, exit);
        }
        return exit == 0;
      } catch (IOException e) {
        LOG.debugf("IOException killing PID %d: %s", pid, e.getMessage());
        return false;
      } catch (InterruptedException e) {
        LOG.debugf("Interrupted killing PID %d", pid);
        Thread.currentThread().interrupt();
        return false;
      }
    } else {
      return ProcessHandle.of(pid).map(ph -> {
        ph.descendants().forEach(ProcessHandle::destroyForcibly);
        return ph.destroyForcibly();
      }).orElse(false);
    }
  }

  @Override
  public boolean killProcessOnPort(final int port) {
    final Optional<Long> pid = findPidOnPort(port);
    if (pid.isEmpty()) {
      return false;
    }
    return killProcessTree(pid.get());
  }

  @Override
  public Optional<Long> findPidOnPort(final int port) {
    if (isWindows()) {
      return findPidOnPortWindows(port);
    }
    return findPidOnPortUnix(port);
  }

  private Optional<Long> findPidOnPortWindows(final int port) {
    try {
      final Process p = new ProcessBuilder("netstat", "-ano", "-p", "TCP")
        .redirectErrorStream(true).start();
      final Pattern portPattern = Pattern.compile(":(\\d+)\\s");
      try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        String line;

        while ((line = reader.readLine()) != null) {
          final Optional<Long> found = parseNetstatLine(line, port, portPattern);
          if (found.isPresent()) {
            return found;
          }
        }
      }
      p.waitFor();
    } catch (IOException | NumberFormatException e) {
      LOG.debugf("findPidOnPortWindows port %d: %s", port, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return Optional.empty();
  }

  private Optional<Long> findPidOnPortUnix(final int port) {
    try {
      final Process p = new ProcessBuilder("lsof", "-ti", ":" + port)
        .redirectErrorStream(true).start();
      try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        final String line = reader.readLine();
        if (Objects.nonNull(line) && !line.isBlank()) {
          return Optional.of(Long.parseLong(line.trim()));
        }
      }
      p.waitFor();
    } catch (IOException | NumberFormatException e) {
      LOG.debugf("findPidOnPortUnix port %d: %s", port, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return Optional.empty();
  }

}
