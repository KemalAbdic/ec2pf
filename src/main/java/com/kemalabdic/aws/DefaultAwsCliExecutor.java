package com.kemalabdic.aws;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.util.List;

@ApplicationScoped
public class DefaultAwsCliExecutor implements AwsCliExecutor {

  @Override
  public Process execute(final List<String> command) throws IOException {
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }

  @Override
  public Process executeDetached(final List<String> command) throws IOException {
    final ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    pb.redirectError(ProcessBuilder.Redirect.DISCARD);
    return pb.start();
  }
}
