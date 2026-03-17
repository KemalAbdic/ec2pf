package com.kemalabdic.aws;

import com.kemalabdic.config.AwsConfig;
import com.kemalabdic.config.Ec2pfConfig;
import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.util.ConsoleOutput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class SsmSessionService implements SsmOperations {

  public static final String CMD_AWS = SsmConstants.CMD_AWS;
  public static final String CMD_SSM = SsmConstants.CMD_SSM;
  public static final String CMD_START_SESSION = SsmConstants.CMD_START_SESSION;
  public static final String DOCUMENT_PORT_FORWARDING = SsmConstants.DOCUMENT_PORT_FORWARDING;
  private static final Logger LOG = Logger.getLogger(SsmSessionService.class);
  private static final String FLAG_REGION = "--region";
  private static final String FLAG_PROFILE = "--profile";
  private static final String MSG_AWS_CLI_ERROR = "AWS CLI error: ";

  private final ProcessOperations processService;
  private final AwsErrorReporter errorReporter;
  private final AwsCliExecutor cliExecutor;
  private final ConsoleOutput console;
  private final int startupAttempts;
  private final long startupCheckIntervalMs;
  private final int cliTimeoutSecs;

  @Inject
  public SsmSessionService(final ProcessOperations processService, final AwsErrorReporter errorReporter,
                           final AwsCliExecutor cliExecutor, final ConsoleOutput console,
                           final Ec2pfConfig config) {
    this.processService = processService;
    this.errorReporter = errorReporter;
    this.cliExecutor = cliExecutor;
    this.console = console;
    this.startupAttempts = config.session().startupAttempts();
    this.startupCheckIntervalMs = config.session().startupCheckIntervalMs();
    this.cliTimeoutSecs = config.session().cliTimeoutSecs();
  }

  private static void addAwsFlags(final List<String> cmd, final AwsConfig config) {
    cmd.add(FLAG_REGION);
    cmd.add(config.region());
    cmd.add(FLAG_PROFILE);
    cmd.add(config.profile());
  }

  public static String localPortParam(final int localPort) {
    return SsmConstants.localPortParam(localPort);
  }

  public Map<String, String> batchLookupInstanceIds(final List<String> serviceNames, final AwsConfig config) {
    final Map<String, String> result = new HashMap<>();
    if (serviceNames.isEmpty()) {
      return result;
    }

    final List<String> command = buildBatchLookupCommand(serviceNames, config);
    Process process = null;
    try {
      process = cliExecutor.execute(command);
      final String output;
      try (final BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      final boolean finished = process.waitFor(cliTimeoutSecs, TimeUnit.SECONDS);
      if (!finished) {
        LOG.warnf("AWS CLI timed out after %d seconds for batch lookup", cliTimeoutSecs);
        console.warn("Timeout", "AWS CLI did not respond within %d seconds".formatted(cliTimeoutSecs));
        process.destroyForcibly();
        return result;
      }
      final int exitCode = process.exitValue();
      if (exitCode != 0) {
        errorReporter.reportAwsError(output, config.profile());
        return result;
      }
      parseBatchOutput(output, result);
    } catch (final IOException e) {
      console.warn(MSG_AWS_CLI_ERROR + e.getMessage());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (Objects.nonNull(process)) {
        process.destroyForcibly();
      }
    }
    return result;
  }

  public Optional<String> lookupInstanceId(final String serviceName, final AwsConfig config) {
    final List<String> command = buildSingleLookupCommand(serviceName, config);
    Process process = null;
    try {
      process = cliExecutor.execute(command);
      String output;
      try (final BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining()).trim();
      }
      final boolean finished = process.waitFor(cliTimeoutSecs, TimeUnit.SECONDS);
      if (!finished) {
        LOG.warnf("AWS CLI timed out after %d seconds for instance lookup of '%s'", cliTimeoutSecs, serviceName);
        console.warn("Timeout", "AWS CLI did not respond within %d seconds".formatted(cliTimeoutSecs));
        process.destroyForcibly();
        return Optional.empty();
      }
      final int exitCode = process.exitValue();
      if (exitCode != 0) {
        errorReporter.reportAwsError(output, config.profile());
        return Optional.empty();
      }
      if (output.isEmpty() || output.equals("None") || output.equals("null")) {
        return Optional.empty();
      }
      output = output.replaceAll("[\"\\s]", "");
      if (output.startsWith("i-")) {
        return Optional.of(output);
      }
      return Optional.empty();
    } catch (final IOException e) {
      console.warn(MSG_AWS_CLI_ERROR + e.getMessage());
      return Optional.empty();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (Objects.nonNull(process)) {
        process.destroyForcibly();
      }
    }
  }

  public Optional<Long> startSession(final int localPort, final int remotePort, final String instanceId, final AwsConfig config) {
    final List<String> command = buildStartSessionCommand(localPort, remotePort, instanceId, config);
    Process process = null;
    boolean handedOff = false;
    try {
      process = cliExecutor.executeDetached(command);
      final long pid = process.pid();

      for (int attempt = 0; attempt < startupAttempts; attempt++) {
        Thread.sleep(startupCheckIntervalMs);
        if (!process.isAlive()) {
          return Optional.empty();
        }
        if (processService.isPortInUse(localPort)) {
          handedOff = true;
          return Optional.of(pid);
        }
      }

      LOG.warnf("Session timed out waiting for port %d to become available", localPort);
      console.warn("Timeout", "session timed out waiting for port %d".formatted(localPort));
      return Optional.empty();
    } catch (final IOException e) {
      console.warn(MSG_AWS_CLI_ERROR + e.getMessage());
      return Optional.empty();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      if (Objects.nonNull(process) && !handedOff) {
        process.destroyForcibly();
      }
    }
  }

  List<String> buildBatchLookupCommand(final List<String> serviceNames, final AwsConfig config) {
    final String namesValue = String.join(",", serviceNames);
    final List<String> cmd = new ArrayList<>();
    cmd.add(SsmConstants.CMD_AWS);
    cmd.add(SsmConstants.CMD_EC2);
    cmd.add(SsmConstants.CMD_DESCRIBE_INSTANCES);
    cmd.add("--filters");
    cmd.add("Name=tag:Name,Values=" + namesValue);
    cmd.add("Name=instance-state-name,Values=running");
    cmd.add("--query");
    cmd.add("Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value,InstanceId]");
    cmd.add("--output");
    cmd.add("text");
    addAwsFlags(cmd, config);
    return cmd;
  }

  List<String> buildSingleLookupCommand(final String serviceName, final AwsConfig config) {
    final List<String> cmd = new ArrayList<>();
    cmd.add(SsmConstants.CMD_AWS);
    cmd.add(SsmConstants.CMD_EC2);
    cmd.add(SsmConstants.CMD_DESCRIBE_INSTANCES);
    cmd.add("--filters");
    cmd.add("Name=tag:Name,Values=" + serviceName);
    cmd.add("Name=instance-state-name,Values=running");
    cmd.add("--query");
    cmd.add("Reservations[0].Instances[0].InstanceId");
    cmd.add("--output");
    cmd.add("text");
    addAwsFlags(cmd, config);
    return cmd;
  }

  List<String> buildStartSessionCommand(final int localPort, final int remotePort,
                                        final String instanceId, final AwsConfig config) {
    final List<String> cmd = new ArrayList<>();
    cmd.add(SsmConstants.CMD_AWS);
    cmd.add(SsmConstants.CMD_SSM);
    cmd.add(SsmConstants.CMD_START_SESSION);
    cmd.add("--target");
    cmd.add(instanceId);
    cmd.add("--document-name");
    cmd.add(SsmConstants.DOCUMENT_PORT_FORWARDING);
    cmd.add("--parameters");
    cmd.add("portNumber=[\"" + remotePort + "\"]," + SsmConstants.localPortParam(localPort));
    addAwsFlags(cmd, config);
    return cmd;
  }

  void parseBatchOutput(final String output, final Map<String, String> result) {
    final String[] lines = output.split("\n");
    for (final String line : lines) {
      final String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      final String[] parts = trimmed.split("\\s+");
      if (parts.length >= 2 && parts[1].startsWith("i-")) {
        result.put(parts[0], parts[1]);
      } else {
        LOG.debugf("Skipping non-instance line in batch output: %s", trimmed);
      }
    }
  }
}
