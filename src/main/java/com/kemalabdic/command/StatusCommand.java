package com.kemalabdic.command;

import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.parser.ConfigParseException;
import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.session.PidFileManager;
import com.kemalabdic.session.SessionInfo;
import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.Messages;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

@Command(name = "status", description = "Show active port-forwarding sessions.", mixinStandardHelpOptions = true)
public class StatusCommand implements Callable<Integer> {

  private final IniConfigParser configParser;
  private final PidFileManager pidFileManager;
  private final ProcessOperations processService;
  private final ConsoleOutput console;
  @ArgGroup(exclusive = true, multiplicity = "1")
  @Nullable StatusTarget target;

  @Inject
  public StatusCommand(final IniConfigParser configParser, final PidFileManager pidFileManager,
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
    final boolean hasError;
    if (target.all) {
      hasError = showAll();
    } else {
      hasError = showByConfig(target.configFile);
    }
    return hasError ? ExitCode.SOFTWARE : ExitCode.OK;
  }

  private boolean showByConfig(final Path configFile) {
    if (!Files.exists(configFile)) {
      console.error(Messages.ERR_CONFIG_NOT_FOUND.formatted(configFile));
      return true;
    }

    PortForwardConfig config;
    try {
      config = configParser.parse(configFile);
    } catch (final ConfigParseException e) {
      console.error(Messages.ERR_CONFIG_PARSE.formatted(e.getMessage()));
      return true;
    }

    final Path pidFile = pidFileManager.pidFileFor(config.configFilePath().toString());
    if (!Files.exists(pidFile)) {
      console.info(Messages.MSG_NO_ACTIVE_SESSIONS_FOR.formatted(config.configLabel()));
      return false;
    }

    return displayPidFile(pidFile);
  }

  private boolean showAll() {
    List<Path> pidFiles;
    try {
      pidFiles = pidFileManager.findAllPidFiles();
    } catch (final IOException e) {
      console.error(Messages.ERR_PID_FILES_FIND.formatted(e.getMessage()));
      return true;
    }

    if (pidFiles.isEmpty()) {
      console.info(Messages.MSG_NO_ACTIVE_SESSIONS);
      return false;
    }

    boolean error = false;
    for (final Path pidFile : pidFiles) {
      error = displayPidFile(pidFile) || error;
      console.blank();
    }
    return error;
  }

  private boolean displayPidFile(final Path pidFile) {
    String label;
    try {
      label = pidFileManager.getLabel(pidFile).orElse(pidFile.getFileName().toString());
    } catch (final IOException e) {
      label = pidFile.getFileName().toString();
    }

    console.info("Sessions", label);

    List<SessionInfo> entries;
    try {
      entries = pidFileManager.readEntries(pidFile);
    } catch (final IOException e) {
      console.error(Messages.ERR_PID_FILE_READ.formatted(e.getMessage()));
      return true;
    }

    if (entries.isEmpty()) {
      console.info("No session entries found");
      return false;
    }

    console.blank();
    console.println(Ansi.AUTO.string(
      "  @|faint %-24s  %-7s  %-8s  %-22s  %-6s  %s|@".formatted(
        "SERVICE", "PORT", "PID", "INSTANCE", "STATUS", "UPTIME")));

    for (final SessionInfo entry : entries) {
      final boolean alive = processService.isProcessAlive(entry.pid());
      final boolean portInUse = processService.isPortInUse(entry.localPort());
      final String status = console.statusLabel(alive && portInUse);
      final String uptime = entry.formatUptime();

      console.println("  %-24s  %-7d  %-8d  %-22s  %s    %s".formatted(
        entry.service(), entry.localPort(), entry.pid(), entry.instanceId(), status, uptime));
    }
    return false;
  }

  static class StatusTarget {
    @Option(names = {"-c", "--config"}, description = "Path to INI config file.")
    Path configFile;

    @Option(names = "--all", description = "Show all active sessions.")
    boolean all;
  }
}
