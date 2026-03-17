package com.kemalabdic.session;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SessionInfo(long pid, String service, int localPort, int remotePort, String instanceId, long startedEpoch) {

  private static final Logger LOG = Logger.getLogger(SessionInfo.class);

  public static Optional<SessionInfo> parse(@Nullable String line) {
    if (Objects.isNull(line) || line.isBlank() || line.startsWith("#")) {
      return Optional.empty();
    }
    String[] parts = line.strip().split(":");
    if (parts.length != 6) {
      LOG.warnf("Malformed PID file entry (expected 6 fields, got %d): %s", parts.length, line);
      return Optional.empty();
    }
    try {
      return Optional.of(new SessionInfo(
        Long.parseLong(parts[0]),
        parts[1],
        Integer.parseInt(parts[2]),
        Integer.parseInt(parts[3]),
        parts[4],
        Long.parseLong(parts[5])));
    } catch (NumberFormatException e) {
      LOG.warnf("Malformed PID file entry (bad number): %s", line);
      return Optional.empty();
    }
  }

  public String formatUptime() {
    final long now = Instant.now().getEpochSecond();
    final long seconds = now - startedEpoch;
    if (seconds < 0) {
      return "0m";
    }

    final Duration d = Duration.ofSeconds(seconds);
    final long days = d.toDays();
    final long hours = d.toHoursPart();
    final long minutes = d.toMinutesPart();

    if (days > 0) {
      return "%dd %dh".formatted(days, hours);
    } else if (hours > 0) {
      return "%dh %dm".formatted(hours, minutes);
    } else {
      return "%dm".formatted(minutes);
    }
  }

  public String toLine() {
    return "%d:%s:%d:%d:%s:%d".formatted(pid, service, localPort, remotePort, instanceId, startedEpoch);
  }
}
