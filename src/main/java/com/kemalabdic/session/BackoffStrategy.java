package com.kemalabdic.session;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class BackoffStrategy {

  private static final int BASE_BACKOFF_SECS = 2;
  private static final int MAX_JITTER_SECS = 2;
  private static final int MAX_EXPONENT = 5;

  private final int maxBackoffSecs;
  private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
  private final Map<String, Long> nextRetryEpoch = new ConcurrentHashMap<>();

  BackoffStrategy(final int maxBackoffSecs) {
    this.maxBackoffSecs = maxBackoffSecs;
  }

  long calculateBackoff(final int failures) {
    long backoff = BASE_BACKOFF_SECS * (1L << Math.min(Math.max(1, failures) - 1, MAX_EXPONENT));
    backoff = Math.min(backoff, maxBackoffSecs);
    backoff += ThreadLocalRandom.current().nextInt(0, MAX_JITTER_SECS + 1);
    return backoff;
  }

  boolean isInBackoff(final String serviceName, final long now) {
    final Long retryAt = nextRetryEpoch.get(serviceName);
    return Objects.nonNull(retryAt) && now < retryAt;
  }

  long secondsUntilRetry(final String serviceName, final long now) {
    final Long retryAt = nextRetryEpoch.get(serviceName);
    if (Objects.isNull(retryAt)) {
      return 0;
    }
    return Math.max(0, retryAt - now);
  }

  int recordFailure(final String serviceName, final long now) {
    final int failures = failureCounts.getOrDefault(serviceName, 0) + 1;
    failureCounts.put(serviceName, failures);
    final long backoff = calculateBackoff(failures);
    nextRetryEpoch.put(serviceName, now + backoff);
    return failures;
  }

  void clearBackoff(final String serviceName) {
    failureCounts.remove(serviceName);
    nextRetryEpoch.remove(serviceName);
  }
}
