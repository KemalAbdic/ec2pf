package com.kemalabdic.session;

import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.process.ProcessOperations.KillResult;
import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class WatchMode {

  private static final Logger LOG = Logger.getLogger(WatchMode.class);
  private static final String VERB_CLEANUP = "Cleanup";

  private final ProcessOperations processService;
  private final PidFileManager pidFileManager;
  private final SessionManager sessionManager;
  private final ConsoleOutput console;
  private final BackoffStrategy backoff;
  private final WatchDisplay display;
  private final int portReleaseAttempts;
  private final long portReleaseIntervalMs;
  private volatile boolean running = true;

  @Inject
  public WatchMode(final ProcessOperations processService, final PidFileManager pidFileManager,
                   final SessionManager sessionManager, final ConsoleOutput console,
                   final Ec2pfConfig config) {
    this.processService = processService;
    this.pidFileManager = pidFileManager;
    this.sessionManager = sessionManager;
    this.console = console;
    this.backoff = new BackoffStrategy(config.watch().maxBackoffSecs());
    this.display = new WatchDisplay(processService, console);
    this.portReleaseAttempts = config.watch().portReleaseAttempts();
    this.portReleaseIntervalMs = config.watch().portReleaseIntervalMs();
  }

  WatchMode(final ProcessOperations processService, final PidFileManager pidFileManager,
            final SessionManager sessionManager, final ConsoleOutput console,
            final BackoffStrategy backoff) {
    this.processService = processService;
    this.pidFileManager = pidFileManager;
    this.sessionManager = sessionManager;
    this.console = console;
    this.backoff = backoff;
    this.display = new WatchDisplay(processService, console);
    this.portReleaseAttempts = 10;
    this.portReleaseIntervalMs = 500;
  }

  public void run(final PortForwardConfig config, final Path pidFile, final int intervalSecs) {
    final Thread mainThread = Thread.currentThread();
    final AtomicBoolean inWatchLoop = new AtomicBoolean(false);
    final Thread shutdownHook = new Thread(() -> {
      running = false;
      if (inWatchLoop.get()) {
        mainThread.interrupt();
      }
    });
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    console.info("Watching", "interval=%ds, press Ctrl+C to stop".formatted(intervalSecs));
    console.blank();

    final Map<String, String> instanceCache = new HashMap<>();

    try {
      final List<SessionInfo> initial = pidFileManager.readEntries(pidFile);
      display.render(initial);
    } catch (final IOException ignored) {
      // best-effort, watch loop continues regardless
    }

    try {
      inWatchLoop.set(true);
      while (running) {
        Thread.sleep(intervalSecs * 1000L);
        if (!running) {
          break;
        }
        checkAndReconnect(config, pidFile, instanceCache);
      }
    } catch (final InterruptedException e) {
      // not re-interrupting
    } finally {
      inWatchLoop.set(false);
      cleanupManagedSessions(pidFile);
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (final IllegalStateException ignored) {
        // JVM already shutting down
      }
    }
  }

  void checkAndReconnect(final PortForwardConfig config, final Path pidFile, final Map<String, String> instanceCache) {
    List<SessionInfo> entries;
    try {
      entries = pidFileManager.readEntries(pidFile);
    } catch (final IOException e) {
      display.addEvent("Failed to read PID file: " + e.getMessage());
      return;
    }

    LOG.debugf("Checking %d session entries", entries.size());

    final long now = Instant.now().getEpochSecond();
    boolean changed = false;

    for (SessionInfo entry : entries) {
      final boolean entryChanged = processEntry(entry, config, pidFile, instanceCache, now);
      changed = changed || entryChanged;
    }

    if (changed) {
      entries = rewritePidFile(config, pidFile);
    }

    display.render(entries);
  }

  private boolean processEntry(final SessionInfo entry, final PortForwardConfig config,
                               final Path pidFile, final Map<String, String> instanceCache, final long now) {
    final boolean alive = processService.isProcessAlive(entry.pid());
    final boolean portOpen = processService.isPortInUse(entry.localPort());

    if (alive && portOpen) {
      return false;
    }

    final String svcName = entry.service();
    LOG.debugf("%s -- session down (alive=%b, portOpen=%b)", svcName, alive, portOpen);

    if (backoff.isInBackoff(svcName, now)) {
      display.addEvent("%s -- waiting for backoff (retry in %ds)".formatted(svcName, backoff.secondsUntilRetry(svcName, now)));
      return false;
    }

    display.addEvent("%s -- session down, reconnecting...".formatted(svcName));

    if (alive) {
      final KillResult killResult = processService.killIfExpected(
        entry.pid(), entry.localPort(), entry.instanceId());
      if (killResult == KillResult.SKIPPED_MISMATCH) {
        display.addEvent("%s -- PID %d skipped (process mismatch)".formatted(svcName, entry.pid()));
        return false;
      }
    }

    if (!ensurePortFree(svcName, entry.localPort(), now)) {
      return false;
    }

    return attemptReconnect(entry, config, pidFile, instanceCache, now);
  }

  private boolean ensurePortFree(final String svcName, final int localPort, final long now) {
    if (waitForPortRelease(localPort)) {
      return true;
    }
    LOG.debugf("%s -- port %d still in use, killing orphan", svcName, localPort);
    final var orphanPid = processService.findPidOnPort(localPort);
    if (orphanPid.isPresent()) {
      final var orphanCmd = processService.getProcessCommand(orphanPid.get());
      if (orphanCmd.isPresent() && !orphanCmd.get().contains(com.kemalabdic.aws.SsmConstants.CMD_SSM)) {
        LOG.warnf("%s -- port %d held by non-SSM process (PID %d), skipping kill", svcName, localPort, orphanPid.get());
        display.addEvent("%s -- port %d held by non-SSM process, skipping".formatted(svcName, localPort));
        backoff.recordFailure(svcName, now);
        display.addEvent("%s -- retry in %ds".formatted(svcName, backoff.secondsUntilRetry(svcName, now)));
        return false;
      }
    }
    display.addEvent("%s -- port %d held by orphan process, killing...".formatted(svcName, localPort));
    processService.killProcessOnPort(localPort);
    if (waitForPortRelease(localPort)) {
      return true;
    }
    backoff.recordFailure(svcName, now);
    display.addEvent("%s -- port %d still in use, retry in %ds".formatted(svcName, localPort,
      backoff.secondsUntilRetry(svcName, now)));
    return false;
  }

  private boolean attemptReconnect(final SessionInfo entry, final PortForwardConfig config,
                                   final Path pidFile, final Map<String, String> instanceCache, final long now) {
    final String svcName = entry.service();
    final ServiceConfig svcConfig = config.services().stream()
      .filter(s -> s.name().equals(svcName))
      .findFirst()
      .orElse(null);

    if (Objects.isNull(svcConfig)) {
      display.addEvent("%s -- service not found in config, dropping".formatted(svcName));
      return true;
    }

    instanceCache.putIfAbsent(svcName, entry.instanceId());

    final SessionManager.SessionResult result = sessionManager.startSession(
      svcConfig, config.awsConfig(), instanceCache, pidFile, false, OutputMode.QUIET);

    if (result == SessionManager.SessionResult.STARTED) {
      LOG.debugf("%s -- reconnect succeeded", svcName);
      display.addEvent("%s -- reconnected".formatted(svcName));
      backoff.clearBackoff(svcName);
      return true;
    }

    final int failures = backoff.recordFailure(svcName, now);
    final long backoffSecs = backoff.secondsUntilRetry(svcName, now);
    LOG.debugf("%s -- reconnect failed (attempt %d, backoff %ds)", svcName, failures, backoffSecs);
    display.addEvent("%s -- reconnect failed (attempt %d, retry in %ds)".formatted(svcName, failures, backoffSecs));
    return false;
  }

  private List<SessionInfo> rewritePidFile(final PortForwardConfig config, final Path pidFile) {
    try {
      final List<SessionInfo> allEntries = pidFileManager.readEntries(pidFile);
      final Map<String, SessionInfo> latest = new LinkedHashMap<>();
      for (final SessionInfo e : allEntries) {
        latest.put(e.service(), e);
      }
      final List<SessionInfo> deduplicated = new ArrayList<>(latest.values());
      final List<SessionInfo> live = deduplicated.stream()
        .filter(e -> processService.isProcessAlive(e.pid()))
        .collect(Collectors.toCollection(ArrayList::new));
      pidFileManager.writeEntries(pidFile, config.configFilePath().toString(), config.configLabel(), live);
      return live;
    } catch (final IOException e) {
      try {
        return pidFileManager.readEntries(pidFile);
      } catch (final IOException ex) {
        return List.of();
      }
    }
  }

  void cleanupManagedSessions(final Path pidFile) {
    LOG.debugf("Cleaning up managed sessions from %s", pidFile);
    console.info("Shutdown", "cleaning up sessions...");
    try {
      final List<SessionInfo> entries = pidFileManager.readEntries(pidFile);
      int stopped = 0;
      final List<SessionInfo> survivors = new java.util.ArrayList<>();
      for (final SessionInfo entry : entries) {
        if (processService.isProcessAlive(entry.pid())) {
          if (killEntry(entry)) {
            stopped++;
          } else {
            survivors.add(entry);
          }
        }
      }
      if (survivors.isEmpty()) {
        pidFileManager.deletePidFile(pidFile);
        console.success(VERB_CLEANUP, Messages.MSG_SESSION_COUNT.formatted(stopped) + " stopped");
      } else {
        final String configPath = pidFileManager.getConfigPath(pidFile).orElse("");
        final String configLabel = pidFileManager.getLabel(pidFile).orElse("");
        pidFileManager.writeEntries(pidFile, configPath, configLabel, survivors);
        console.warn(VERB_CLEANUP, "%d stopped, %d still running".formatted(stopped, survivors.size()));
      }
    } catch (final IOException e) {
      console.error(VERB_CLEANUP, e.getMessage());
    }
  }

  private boolean killEntry(final SessionInfo entry) {
    try {
      final KillResult killResult = processService.killIfExpected(
        entry.pid(), entry.localPort(), entry.instanceId());
      switch (killResult) {
        case KILLED, KILLED_LIMITED -> {
          return true;
        }
        case SKIPPED_MISMATCH -> LOG.warnf("PID %d for %s skipped - process mismatch", entry.pid(), entry.service());
        case FAILED -> LOG.warnf("Failed to kill PID %d for %s", entry.pid(), entry.service());
      }
    } catch (final Exception e) {
      LOG.warnf("Failed to kill PID %d for %s: %s", entry.pid(), entry.service(), e.getMessage());
    }
    return false;
  }

  boolean waitForPortRelease(final int port) {
    for (int i = 0; i < portReleaseAttempts; i++) {
      if (!processService.isPortInUse(port)) {
        return true;
      }
      try {
        Thread.sleep(portReleaseIntervalMs);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return !processService.isPortInUse(port);
  }

  WatchDisplay getDisplay() {
    return display;
  }
}
