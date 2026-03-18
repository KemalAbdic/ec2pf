package com.kemalabdic;

import com.kemalabdic.command.StartCommand;
import com.kemalabdic.command.StatusCommand;
import com.kemalabdic.command.StopCommand;
import com.kemalabdic.command.ValidateCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import picocli.AutoComplete.GenerateCompletion;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

@TopCommand
@Command(name = "ec2pf",
  mixinStandardHelpOptions = true,
  subcommands = {StartCommand.class, StopCommand.class, StatusCommand.class, ValidateCommand.class,
    GenerateCompletion.class},
  versionProvider = PortForwardCommand.VersionProvider.class,
  description = "Manage AWS SSM port-forwarding sessions to EC2 instances.")
public class PortForwardCommand {
  private PortForwardCommand() {
    // not instantiated
  }


  static class VersionProvider implements IVersionProvider {

    private VersionProvider() {
      // not instantiated
    }

    @Override
    public String[] getVersion() {
      final String version = loadVersion();
      return new String[] {
        "ec2pf %s (Java %s / %s %s)".formatted(
          version,
          System.getProperty("java.version"),
          System.getProperty("os.name"),
          System.getProperty("os.arch"))
      };
    }

    private String loadVersion() {
      try (InputStream is = getClass().getClassLoader().getResourceAsStream("version.properties")) {
        if (Objects.nonNull(is)) {
          final Properties props = new Properties();
          props.load(is);
          return props.getProperty("app.version", "unknown");
        }
      } catch (final IOException ignored) {
        // fall back to "unknown"
      }
      return "unknown";
    }
  }
}
