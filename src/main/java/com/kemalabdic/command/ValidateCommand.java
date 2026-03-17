package com.kemalabdic.command;

import com.kemalabdic.config.PortForwardConfig;
import com.kemalabdic.config.ServiceConfig;
import com.kemalabdic.config.parser.ConfigParseException;
import com.kemalabdic.config.parser.IniConfigParser;
import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.Messages;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;

@Command(name = "validate", description = "Validate an INI config file.", mixinStandardHelpOptions = true)
public class ValidateCommand implements Callable<Integer> {

  private final IniConfigParser configParser;
  private final ConsoleOutput console;
  @Option(names = {"-c", "--config"}, required = true, description = "Path to INI config file.")
  @Nullable Path configFile;

  @Inject
  public ValidateCommand(final IniConfigParser configParser, final ConsoleOutput console) {
    this.configParser = configParser;
    this.console = console;
  }

  @Override
  public Integer call() {
    if (!Files.exists(configFile)) {
      console.error(Messages.ERR_CONFIG_NOT_FOUND.formatted(configFile));
      return ExitCode.SOFTWARE;
    }

    PortForwardConfig config;
    try {
      config = configParser.parse(configFile);
    } catch (final ConfigParseException e) {
      console.error(Messages.ERR_CONFIG_PARSE.formatted(e.getMessage()));
      return ExitCode.SOFTWARE;
    }

    console.success("Valid", configFile.toString());
    console.blank();
    console.info("Region", config.awsConfig().region());
    console.info("Profile", config.awsConfig().profile());
    console.info("Port", String.valueOf(config.awsConfig().remotePort()));
    console.info("Services", Messages.MSG_SERVICES_DEFINED.formatted(config.services().size()));
    console.blank();

    for (final ServiceConfig service : config.services()) {
      final String portOverride = service.remotePort() != config.awsConfig().remotePort()
        ? " -> :%d".formatted(service.remotePort())
        : "";
      final String detail = ":%d%s".formatted(service.localPort(), portOverride);
      if (service.skip()) {
        console.skip(service.name(), detail);
      } else {
        console.info(service.name(), detail);
      }
    }
    return ExitCode.OK;
  }
}
