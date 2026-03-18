package com.kemalabdic.command;

import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.parser.ConfigParseException;
import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.process.ProcessOperations.KillResult;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.Messages;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

@Command(name = "stop", description = "Stop port-forwarding sessions.", mixinStandardHelpOptions = true)
public class StopCommand implements Callable<Integer> {

  private final IniConfigParser configParser;
  private final PidFileManager pidFileManager;
  private final ProcessOperations processService;
  private final ConsoleOutput console;
  @ArgGroup(multiplicity = "1")
  @Nullable StopTarget target;
  @Option(names = "--dry-run", description = "Show what would be stopped without killing processes.")
  boolean dryRun;

  @Inject
  public StopCommand(final IniConfigParser configParser, final PidFileManager pidFileManager,
                     final ProcessOperations processService, final ConsoleOutput console) {
    this.configParser = configParser;
    this.pidFileManager = pidFileManager;
    this.processService = processService;
    this.console = console;
  }

  @Override
  public Integer call() {
    if (Objects.isNull(target)) {
      console.error("No target specified. Use -c/--config or --all.");
      return ExitCode.SOFTWARE;
    }
    final ResultCounts totalCounts;
    if (target.all) {
      totalCounts = stopAll();
    } else {
      totalCounts = stopByConfig(Objects.requireNonNull(target.configFile));
    }
    return totalCounts.failed() > 0 ? ExitCode.SOFTWARE : ExitCode.OK;
  }

  private ResultCounts stopByConfig(final Path configFile) {
    if (!Files.exists(configFile)) {
      console.error(Messages.ERR_CONFIG_NOT_FOUND.formatted(configFile));
      return new ResultCounts(0, 0, 1);
    }

    PortForwardConfig config;
    try {
      config = configParser.parse(configFile);
    } catch (final ConfigParseException e) {
      console.error(Messages.ERR_CONFIG_PARSE.formatted(e.getMessage()));
      return new ResultCounts(0, 0, 1);
    }

    final List<Path> pidFiles = new ArrayList<>();

    final Path hashPidFile = pidFileManager.pidFileFor(config.configFilePath().toString());
    if (Files.exists(hashPidFile)) {
      pidFiles.add(hashPidFile);
    }

    final Path legacyPidFile = pidFileManager.legacyPidFile(config.configLabel());
    if (Files.exists(legacyPidFile) && !legacyPidFile.equals(hashPidFile)) {
      pidFiles.add(legacyPidFile);
    }

    if (pidFiles.isEmpty()) {
      console.info(Messages.MSG_NO_ACTIVE_SESSIONS_FOR.formatted(config.configLabel()));
      return new ResultCounts(0, 0, 0);
    }

    ResultCounts total = new ResultCounts(0, 0, 0);
    for (final Path pidFile : pidFiles) {
      total = total.add(stopFromPidFile(pidFile));
    }
    return total;
  }

  private ResultCounts stopAll() {
    List<Path> pidFiles;
    try {
      pidFiles = pidFileManager.findAllPidFiles();
    } catch (final IOException e) {
      console.error(Messages.ERR_PID_FILES_FIND.formatted(e.getMessage()));
      return new ResultCounts(0, 0, 1);
    }

    if (pidFiles.isEmpty()) {
      console.info(Messages.MSG_NO_ACTIVE_SESSIONS);
      return new ResultCounts(0, 0, 0);
    }

    console.info("Found", "%d PID file(s)".formatted(pidFiles.size()));
    ResultCounts total = new ResultCounts(0, 0, 0);
    for (final Path pidFile : pidFiles) {
      total = total.add(stopFromPidFile(pidFile));
    }
    return total;
  }

  private ResultCounts stopFromPidFile(final Path pidFile) {
    String label;
    try {
      label = pidFileManager.getLabel(pidFile).orElse(pidFile.getFileName().toString());
    } catch (final IOException e) {
      label = pidFile.getFileName().toString();
    }

    console.info("Stopping", label);

    List<SessionInfo> entries;
    try {
      entries = pidFileManager.readEntries(pidFile);
    } catch (final IOException e) {
      console.error(Messages.ERR_PID_FILE_READ.formatted(e.getMessage()));
      return new ResultCounts(0, 0, 0);
    }

    if (entries.isEmpty()) {
      console.info("No session entries in %s".formatted(pidFile.getFileName()));
      try {
        pidFileManager.deletePidFile(pidFile);
      } catch (final IOException ignored) {
        // best-effort, missing file is fine
      }
      return new ResultCounts(0, 0, 0);
    }

    ResultCounts counts = new ResultCounts(0, 0, 0);

    for (final SessionInfo entry : entries) {
      counts = counts.add(stopEntry(entry));
    }

    if (!dryRun) {
      try {
        pidFileManager.deletePidFile(pidFile);
      } catch (final IOException e) {
        console.warn(Messages.ERR_PID_FILE_REMOVE.formatted(e.getMessage()));
      }
    }

    printStopSummary(counts);
    return counts;
  }

  private ResultCounts stopEntry(final SessionInfo entry) {
    if (dryRun) {
      console.dryRun("Would stop", "%s (PID %d, port %d)".formatted(entry.service(), entry.pid(), entry.localPort()));
      return new ResultCounts(1, 0, 0);
    }
    final KillResult result = processService.killIfExpected(
      entry.pid(), entry.localPort(), entry.instanceId());
    return switch (result) {
      case KILLED -> {
        console.success("Stopped", "%s (PID %d, %s)".formatted(
          entry.service(), entry.pid(), entry.formatUptime()));
        yield new ResultCounts(1, 0, 0);
      }
      case KILLED_LIMITED -> {
        console.warn("Stopped", "%s (PID %d, %s, limited validation)".formatted(
          entry.service(), entry.pid(), entry.formatUptime()));
        yield new ResultCounts(1, 0, 0);
      }
      case SKIPPED_MISMATCH -> {
        console.skip("Skipped", "%s (PID %d) not running or PID mismatch".formatted(entry.service(), entry.pid()));
        yield new ResultCounts(0, 1, 0);
      }
      case FAILED -> {
        console.error("Failed", "%s (PID %d) could not stop".formatted(entry.service(), entry.pid()));
        yield new ResultCounts(0, 0, 1);
      }
    };
  }

  private void printStopSummary(final ResultCounts counts) {
    console.blank();
    if (dryRun) {
      console.dryRun("Summary", "%d would be stopped".formatted(counts.succeeded()));
    } else {
      console.summary(counts.succeeded(), counts.skipped(), counts.failed());
    }
  }

  static class StopTarget {
    @Option(names = {"-c", "--config"}, description = "Path to INI config file.")
    @Nullable Path configFile;

    @Option(names = "--all", description = "Stop all active sessions.")
    boolean all;
  }
}
