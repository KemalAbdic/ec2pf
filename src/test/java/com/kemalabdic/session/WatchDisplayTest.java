package com.kemalabdic.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.util.ConsoleOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

class WatchDisplayTest {

  private ProcessOperations processService;
  private ByteArrayOutputStream capturedOut;
  private ConsoleOutput console;
  private WatchDisplay display;

  @BeforeEach
  void setUp() {
    processService = mock(ProcessOperations.class);
    capturedOut = new ByteArrayOutputStream();
    console = new ConsoleOutput(new PrintStream(capturedOut), false);
    display = new WatchDisplay(processService, console);
  }

  private String output() {
    return capturedOut.toString();
  }

  @Test
  void eventLogCapsAtFiveEntries() {
    // when
    for (int i = 0; i < 7; i++) {
      display.addEvent("event-" + i);
    }

    // then
    assertEquals(5, display.eventLogSize(), "Event log should be capped at 5");
  }

  @Test
  void eventLogMaintainsFifoOrder() {
    // given
    display.addEvent("first");
    display.addEvent("second");
    display.addEvent("third");

    // when
    when(processService.isProcessAlive(anyLong())).thenReturn(true);
    when(processService.isPortInUse(anyInt())).thenReturn(true);
    display.render(List.of());

    // then - most recent event ("third") should appear first in output
    final String out = output();
    final int thirdIdx = out.indexOf("third");
    final int firstIdx = out.indexOf("first");
    assertTrue(thirdIdx >= 0, "Should contain 'third' event");
    assertTrue(firstIdx >= 0, "Should contain 'first' event");
    assertTrue(thirdIdx < firstIdx, "Most recent event should appear before older events");
  }

  @Test
  void renderOutputContainsServiceTableHeaders() {
    // given
    when(processService.isProcessAlive(anyLong())).thenReturn(true);
    when(processService.isPortInUse(anyInt())).thenReturn(true);

    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    // when
    display.render(List.of(entry));

    // then
    final String out = output();
    assertTrue(out.contains("SERVICE"), "Should contain SERVICE header");
    assertTrue(out.contains("PORT"), "Should contain PORT header");
    assertTrue(out.contains("PID"), "Should contain PID header");
    assertTrue(out.contains("STATUS"), "Should contain STATUS header");
  }

  @Test
  void renderOutputContainsServiceDetails() {
    // given
    when(processService.isProcessAlive(1234L)).thenReturn(true);
    when(processService.isPortInUse(7001)).thenReturn(true);

    final SessionInfo entry = new SessionInfo(1234L, "my-svc", 7001, 8080, "i-abc123", 1000L);

    // when
    display.render(List.of(entry));

    // then
    final String out = output();
    assertTrue(out.contains("my-svc"), "Should contain service name");
    assertTrue(out.contains("7001"), "Should contain port");
    assertTrue(out.contains("1234"), "Should contain PID");
  }

  @Test
  void deduplicateKeepsLatestEntryPerService() {
    // given
    when(processService.isProcessAlive(anyLong())).thenReturn(true);
    when(processService.isPortInUse(anyInt())).thenReturn(true);

    final SessionInfo old = new SessionInfo(1000L, "my-svc", 7001, 8080, "i-old", 1000L);
    final SessionInfo latest = new SessionInfo(2000L, "my-svc", 7001, 8080, "i-new", 2000L);

    // when
    display.render(List.of(old, latest));

    // then - should contain the latest PID, not the old one
    final String out = output();
    assertTrue(out.contains("2000"), "Should contain latest PID 2000");
    // The old PID should not appear in the service rows (only one row per service)
    // Count occurrences of "my-svc" in the output - should be exactly once in the data rows
    final long svcCount = out.lines()
      .filter(line -> line.contains("my-svc") && !line.contains("SERVICE"))
      .count();
    assertEquals(1, svcCount, "Should have exactly one row for deduplicated service");
  }

  @Test
  void secondRenderPadsOutputToOverwritePreviousDisplay() {
    // given
    when(processService.isProcessAlive(anyLong())).thenReturn(true);
    when(processService.isPortInUse(anyInt())).thenReturn(true);

    final SessionInfo entry1 = new SessionInfo(1234L, "svc-a", 7001, 8080, "i-abc", 1000L);
    final SessionInfo entry2 = new SessionInfo(5678L, "svc-b", 7002, 8080, "i-def", 1000L);

    // when - first render with two entries
    display.render(List.of(entry1, entry2));
    capturedOut.reset();

    // second render with only one entry (should still produce at least as many lines)
    display.render(List.of(entry1));

    // then - the second render should produce output (padding lines to overwrite)
    final String out = output();
    assertFalse(out.isEmpty(), "Second render should produce output for overwriting");
  }
}
