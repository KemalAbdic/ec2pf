package com.kemalabdic.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "ec2pf")
public interface Ec2pfConfig {

  SessionConfig session();

  WatchConfig watch();

  PidConfig pid();

  interface SessionConfig {
    @WithDefault("10")
    int startupAttempts();

    @WithDefault("1000")
    long startupCheckIntervalMs();

    @WithDefault("30")
    int cliTimeoutSecs();
  }

  interface WatchConfig {
    @WithDefault("60")
    int maxBackoffSecs();

    @WithDefault("30")
    int defaultIntervalSecs();

    @WithDefault("5")
    int minIntervalSecs();

    @WithDefault("10")
    int portReleaseAttempts();

    @WithDefault("500")
    long portReleaseIntervalMs();
  }

  interface PidConfig {
    @WithDefault("${user.dir}")
    String directory();
  }
}
