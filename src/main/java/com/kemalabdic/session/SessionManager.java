package com.kemalabdic.session;

import com.kemalabdic.aws.SsmOperations;
import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class SessionManager {

  private final SsmOperations ssmSessionService;
  private final ProcessOperations processService;
  private final PidFileManager pidFileManager;
  private final ConsoleOutput console;

  @Inject
  public SessionManager(final SsmOperations ssmSessionService, final ProcessOperations processService,
                        final PidFileManager pidFileManager, final ConsoleOutput console) {
    this.ssmSessionService = ssmSessionService;
    this.processService = processService;
    this.pidFileManager = pidFileManager;
    this.console = console;
  }

  public Map<String, String> prefetchInstanceIds(final List<ServiceConfig> services, final AwsConfig config) {
    final List<String> names = services.stream()
      .filter(s -> !s.skip())
      .map(ServiceConfig::name)
      .distinct()
      .toList();
    if (names.isEmpty()) {
      return new HashMap<>();
    }
    console.info("Resolving", "%d instance ID(s)...".formatted(names.size()));
    final Map<String, String> result = ssmSessionService.batchLookupInstanceIds(names, config);
    if (!result.isEmpty()) {
      console.success("Resolved", "%d instance ID(s)".formatted(result.size()));
    }
    return new HashMap<>(result);
  }

  public SessionResult startSession(final ServiceConfig service, final AwsConfig config,
                                    final Map<String, String> instanceCache, final Path pidFile, final boolean dryRun) {
    return startSession(service, config, instanceCache, pidFile, dryRun, OutputMode.NORMAL);
  }

  public SessionResult startSession(final ServiceConfig service, final AwsConfig config,
                                    final Map<String, String> instanceCache, final Path pidFile, final boolean dryRun,
                                    final OutputMode outputMode) {

    if (service.skip()) {
      if (outputMode == OutputMode.NORMAL) {
        console.skip(service.name(), "skip=true");
      }
      return SessionResult.SKIPPED;
    }

    if (processService.isPortInUse(service.localPort())) {
      if (outputMode == OutputMode.NORMAL) {
        console.warn(service.name(), "port %d already in use".formatted(service.localPort()));
      }
      return SessionResult.SKIPPED;
    }

    final Optional<String> instanceIdOpt = resolveInstanceId(service, config, instanceCache, outputMode);
    if (instanceIdOpt.isEmpty()) {
      return SessionResult.FAILED;
    }
    final String instanceId = instanceIdOpt.get();

    if (dryRun) {
      if (outputMode == OutputMode.NORMAL) {
        console.dryRun(service.name(), "would connect %s (:%d -> :%d)".formatted(
          instanceId, service.localPort(), service.remotePort()));
      }
      return SessionResult.STARTED;
    }

    return executeSession(service, config, instanceId, pidFile, outputMode);
  }

  private Optional<String> resolveInstanceId(final ServiceConfig service, final AwsConfig config,
                                             final Map<String, String> instanceCache, final OutputMode outputMode) {
    final String cached = instanceCache.get(service.name());
    if (Objects.nonNull(cached)) {
      return Optional.of(cached);
    }
    if (outputMode == OutputMode.NORMAL) {
      console.info(service.name(), "looking up instance ID...");
    }
    final Optional<String> looked = ssmSessionService.lookupInstanceId(service.name(), config);
    if (looked.isEmpty()) {
      if (outputMode == OutputMode.NORMAL) {
        console.error(service.name(), "instance not found");
      }
      return Optional.empty();
    }
    final String instanceId = looked.get();
    instanceCache.put(service.name(), instanceId);
    return Optional.of(instanceId);
  }

  private SessionResult executeSession(final ServiceConfig service, final AwsConfig config,
                                       final String instanceId, final Path pidFile, final OutputMode outputMode) {
    if (outputMode == OutputMode.NORMAL) {
      console.info("Connecting", "%s %s (:%d -> :%d)".formatted(
        service.name(), instanceId, service.localPort(), service.remotePort()));
    }

    final Optional<Long> pidOpt = ssmSessionService.startSession(
      service.localPort(), service.remotePort(), instanceId, config);

    if (pidOpt.isEmpty()) {
      if (outputMode == OutputMode.NORMAL) {
        console.error(service.name(), "session failed to start");
      }
      return SessionResult.FAILED;
    }

    final long pid = pidOpt.get();
    if (outputMode == OutputMode.NORMAL) {
      console.success("Connected", "%s (PID %d, port %d)".formatted(service.name(), pid, service.localPort()));
    }

    return recordSession(service, instanceId, pid, pidFile, outputMode);
  }

  private SessionResult recordSession(final ServiceConfig service, final String instanceId,
                                      final long pid, final Path pidFile, final OutputMode outputMode) {
    final SessionInfo entry = new SessionInfo(pid, service.name(), service.localPort(),
      service.remotePort(), instanceId, Instant.now().getEpochSecond());
    try {
      pidFileManager.appendEntry(pidFile, entry);
    } catch (final IOException e) {
      if (outputMode == OutputMode.NORMAL) {
        console.warn(Messages.ERR_PID_FILE_WRITE + e.getMessage());
      }
      processService.killProcessTree(pid);
      return SessionResult.FAILED;
    }

    return SessionResult.STARTED;
  }

  public enum SessionResult {
    STARTED, SKIPPED, FAILED
  }
}
