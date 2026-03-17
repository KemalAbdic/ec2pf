package com.kemalabdic.command;

import com.kemalabdic.aws.AwsErrorReporter;
import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.config.parser.ConfigParseException;
import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.process.ProcessOperations.KillResult;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
import com.kemalabdic.session.SessionManager;
import com.kemalabdic.session.WatchMode;
import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.Messages;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

@Command(name = "start", description = "Start port-forwarding sessions.", mixinStandardHelpOptions = true)
public class StartCommand implements Callable<Integer> {

  private final IniConfigParser configParser;
  private final PidFileManager pidFileManager;
  private final ProcessOperations processService;
  private final SessionManager sessionManager;
  private final WatchMode watchMode;
  private final AwsErrorReporter errorReporter;
  private final ConsoleOutput console;
  private final int defaultWatchInterval;
  private final int minWatchInterval;
  @Option(names = {"-c", "--config"}, required = true, description = "Path to INI config file.")
  @Nullable Path configFile;
  @Option(names = "--dry-run", description = "Show what would be done without connecting.")
  boolean dryRun;
  @ArgGroup
  @Nullable WatchGroup watchGroup;

  @Inject
  public StartCommand(final IniConfigParser configParser, final PidFileManager pidFileManager,
                      final ProcessOperations processService,
                      final SessionManager sessionManager, final WatchMode watchMode,
                      final AwsErrorReporter errorReporter, final ConsoleOutput console,
                      final Ec2pfConfig config) {
    this.configParser = configParser;
    this.pidFileManager = pidFileManager;
    this.processService = processService;
    this.sessionManager = sessionManager;
    this.watchMode = watchMode;
    this.errorReporter = errorReporter;
    this.console = console;
    this.defaultWatchInterval = config.watch().defaultIntervalSecs();
    this.minWatchInterval = config.watch().minIntervalSecs();
  }

  @Override
  public Integer call() {
    errorReporter.reset();

    if (!Files.exists(configFile)) {
      console.error(Messages.ERR_CONFIG_NOT_FOUND.formatted(configFile));
      return ExitCode.SOFTWARE;
    }

    final Optional<PortForwardConfig> configOpt = parseConfig();
    if (configOpt.isEmpty()) {
      return ExitCode.SOFTWARE;
    }
    final PortForwardConfig config = configOpt.get();

    printConfigSummary(config);

    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());
    cleanupPrevious(pidFile);

    if (!ensurePidFile(pidFile, config)) {
      return ExitCode.SOFTWARE;
    }

    final Map<String, String> instanceCache = sessionManager.prefetchInstanceIds(
      config.services(), config.awsConfig());

    final ResultCounts counts = connectServices(config, instanceCache, pidFile);

    console.blank();
    printResultSummary(counts);

    if (counts.succeeded() == 0 && counts.failed() > 0) {
      return ExitCode.SOFTWARE;
    }

    startWatchModeIfEnabled(config, pidFile, counts.succeeded());
    return ExitCode.OK;
  }

  private Optional<PortForwardConfig> parseConfig() {
    try {
      return Optional.of(configParser.parse(configFile));
    } catch (ConfigParseException e) {
      console.error(Messages.ERR_CONFIG_PARSE.formatted(e.getMessage()));
      return Optional.empty();
    }
  }

  private void printConfigSummary(final PortForwardConfig config) {
    console.info("Config", "%s (%s)".formatted(config.configLabel(), config.configFilePath()));
    console.info("Region", config.awsConfig().region());
    console.info("Profile", config.awsConfig().profile());
    console.info("Port", String.valueOf(config.awsConfig().remotePort()));
    console.info("Services", Messages.MSG_SERVICES_DEFINED.formatted(config.services().size()));
    console.blank();
  }

  private boolean ensurePidFile(final Path pidFile, final PortForwardConfig config) {
    try {
      pidFileManager.ensureHeader(pidFile, config.configFilePath().toString(), config.configLabel());
      return true;
    } catch (IOException e) {
      console.error(Messages.ERR_PID_FILE_CREATE.formatted(e.getMessage()));
      return false;
    }
  }

  private ResultCounts connectServices(final PortForwardConfig config, final Map<String, String> instanceCache,
                                       final Path pidFile) {
    ResultCounts counts = new ResultCounts(0, 0, 0);
    for (final ServiceConfig service : config.services()) {
      final SessionManager.SessionResult result = sessionManager.startSession(
        service, config.awsConfig(), instanceCache, pidFile, dryRun);
      counts = switch (result) {
        case STARTED -> counts.add(new ResultCounts(1, 0, 0));
        case SKIPPED -> counts.add(new ResultCounts(0, 1, 0));
        case FAILED -> counts.add(new ResultCounts(0, 0, 1));
      };
    }
    return counts;
  }

  private void printResultSummary(final ResultCounts counts) {
    console.summary(counts.succeeded(), counts.skipped(), counts.failed());
  }

  private void startWatchModeIfEnabled(final PortForwardConfig config, final Path pidFile, final int started) {
    if (dryRun || started <= 0) {
      return;
    }

    boolean watchEnabled = true;
    int watchInterval = defaultWatchInterval;

    if (Objects.nonNull(watchGroup)) {
      if (watchGroup.noWatch) {
        watchEnabled = false;
      } else if (Objects.nonNull(watchGroup.watchInterval)) {
        watchInterval = watchGroup.watchInterval;
        if (watchInterval < minWatchInterval) {
          console.warn("Watch interval must be at least %d seconds, using %d".formatted(
            minWatchInterval, minWatchInterval));
          watchInterval = minWatchInterval;
        }
      }
    }

    if (watchEnabled) {
      console.blank();
      watchMode.run(config, pidFile, watchInterval);
    }
  }

  private void cleanupPrevious(final Path pidFile) {
    try {
      final List<SessionInfo> existing = pidFileManager.readEntries(pidFile);
      if (existing.isEmpty()) {
        return;
      }
      console.info("Cleanup", "%d previous session(s)...".formatted(existing.size()));
      for (final SessionInfo entry : existing) {
        if (processService.isProcessAlive(entry.pid())) {
          final KillResult result = processService.killIfExpected(
            entry.pid(), entry.localPort(), entry.instanceId());
          if (result == KillResult.SKIPPED_MISMATCH) {
            console.warn("Cleanup", "PID %d (%s) skipped - process mismatch".formatted(
              entry.pid(), entry.service()));
          }
        }
      }
      pidFileManager.deletePidFile(pidFile);
    } catch (IOException e) {
      console.warn(Messages.ERR_PID_FILE_READ.formatted(e.getMessage()));
    }
  }

  static class WatchGroup {
    @Option(names = "--watch", arity = "0..1", fallbackValue = "30",
      description = "Enable watch mode with interval in seconds (default: 30).")
    Integer watchInterval;

    @Option(names = "--no-watch", description = "Disable watch mode.")
    boolean noWatch;
  }
}
