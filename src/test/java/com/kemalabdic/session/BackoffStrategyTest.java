package com.kemalabdic.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackoffStrategyTest {

  private BackoffStrategy strategy;

  @BeforeEach
  void setUp() {
    strategy = new BackoffStrategy(60);
  }

  @Test
  void backoffZeroFailuresIsAtLeastBase() {
    // given
    // when
    final long backoff = strategy.calculateBackoff(0);

    // then
    // failures=0 is clamped to 1, so base (2) + jitter (0-2) = 2-4
    assertTrue(backoff >= 2, "Expected >= 2 (BASE_BACKOFF_SECS) but got " + backoff);
  }

  @Test
  void backoffFirstFailureIsBase() {
    // given
    // when
    final long backoff = strategy.calculateBackoff(1);

    // then
    // Base = 2, jitter = 0-2, so range is 2-4
    assertTrue(backoff >= 2 && backoff <= 4, "Expected 2-4 but got " + backoff);
  }

  @Test
  void backoffGrowsExponentially() {
    // given
    // when
    final long backoff = strategy.calculateBackoff(3);

    // then
    // b3 base (8) should be > b1 max (4)
    assertTrue(backoff >= 8);
  }

  @Test
  void backoffCappedAt60() {
    // given
    // when
    final long backoff = strategy.calculateBackoff(100);

    // then
    // Max = 60 + jitter(0-2) = 62
    assertTrue(backoff <= 62, "Expected <= 62 but got " + backoff);
  }

  @Test
  void backoffAlwaysPositive() {
    // given
    // when
    // then
    for (int i = 1; i <= 20; i++) {
      assertTrue(strategy.calculateBackoff(i) > 0);
    }
  }

  @Test
  void notInBackoffInitially() {
    // given
    // when
    // then
    assertFalse(strategy.isInBackoff("svc-a", 1000L));
  }

  @Test
  void inBackoffAfterFailure() {
    // given
    strategy.recordFailure("svc-a", 1000L);

    // when
    // then
    assertTrue(strategy.isInBackoff("svc-a", 1001L));
  }

  @Test
  void outOfBackoffAfterEnoughTime() {
    // given
    strategy.recordFailure("svc-a", 1000L);

    // when
    // then
    // After max possible backoff (62s), should be out of backoff
    assertFalse(strategy.isInBackoff("svc-a", 1100L));
  }

  @Test
  void clearBackoffRemovesState() {
    // given
    strategy.recordFailure("svc-a", 1000L);
    assertTrue(strategy.isInBackoff("svc-a", 1001L));

    // when
    strategy.clearBackoff("svc-a");

    // then
    assertFalse(strategy.isInBackoff("svc-a", 1001L));
  }

  @Test
  void secondsUntilRetryReturnsZeroWhenNotInBackoff() {
    // given
    // when
    // then
    assertEquals(0, strategy.secondsUntilRetry("svc-a", 1000L));
  }

  @Test
  void secondsUntilRetryReturnsPositiveInBackoff() {
    // given
    strategy.recordFailure("svc-a", 1000L);

    // when
    // then
    assertTrue(strategy.secondsUntilRetry("svc-a", 1001L) > 0);
  }

  @Test
  void recordFailureReturnsIncreasingCount() {
    // given
    // when
    // then
    assertEquals(1, strategy.recordFailure("svc-a", 1000L));
    assertEquals(2, strategy.recordFailure("svc-a", 2000L));
    assertEquals(3, strategy.recordFailure("svc-a", 3000L));
  }

  @Test
  void independentServicesTrackSeparately() {
    // given
    strategy.recordFailure("svc-a", 1000L);

    // when
    // then
    assertFalse(strategy.isInBackoff("svc-b", 1001L));
    assertEquals(1, strategy.recordFailure("svc-b", 1001L));
  }

  @Test
  void customMaxBackoff() {
    // given
    final BackoffStrategy custom = new BackoffStrategy(5);

    // when
    final long backoff = custom.calculateBackoff(100);

    // then
    // Max = 5 + jitter(0-2) = 7
    assertTrue(backoff <= 7, "Expected <= 7 but got " + backoff);
  }
}
