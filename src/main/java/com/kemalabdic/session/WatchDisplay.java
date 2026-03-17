package com.kemalabdic.session;

import com.kemalabdic.process.ProcessOperations;
import com.kemalabdic.util.ConsoleOutput;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import picocli.CommandLine.Help.Ansi;

final class WatchDisplay {

  private static final int MAX_LOG_ENTRIES = 5;
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final ProcessOperations processService;
  private final ConsoleOutput console;
  private final ConcurrentLinkedDeque<String> eventLog = new ConcurrentLinkedDeque<>();
  private volatile int lastDisplayLines;

  WatchDisplay(final ProcessOperations processService, final ConsoleOutput console) {
    this.processService = processService;
    this.console = console;
  }

  void addEvent(final String message) {
    final String timestamp = LocalTime.now().format(TIME_FMT);
    eventLog.addFirst("[%s] %s".formatted(timestamp, message));
    while (eventLog.size() > MAX_LOG_ENTRIES) {
      eventLog.pollLast();
    }
  }

  void render(final List<SessionInfo> entries) {
    final List<SessionInfo> unique = deduplicateByService(entries);
    final List<String> logSnapshot = List.copyOf(eventLog);

    if (lastDisplayLines > 0) {
      console.cursorUp(lastDisplayLines);
      for (int i = 0; i < lastDisplayLines; i++) {
        console.clearLine();
        console.println("");
      }
      console.cursorUp(lastDisplayLines);
    }

    final List<String> lines = new ArrayList<>();
    lines.add(Ansi.AUTO.string("@|faint " + "-".repeat(56) + "|@"));
    lines.add(Ansi.AUTO.string(
      "  @|faint %-28s  %-7s  %-8s  %s|@".formatted("SERVICE", "PORT", "PID", "STATUS")));
    for (final SessionInfo e : unique) {
      final boolean alive = processService.isProcessAlive(e.pid());
      final boolean portOpen = processService.isPortInUse(e.localPort());
      final String status = console.statusLabel(alive && portOpen);
      lines.add("  %-28s  %-7d  %-8d  %s".formatted(
        e.service(), e.localPort(), e.pid(), status));
    }
    lines.add("");
    lines.add(Ansi.AUTO.string("@|faint " + "-".repeat(56) + "|@"));
    for (final String event : logSnapshot) {
      lines.add(Ansi.AUTO.string("  @|faint " + event + "|@"));
    }

    while (lines.size() < lastDisplayLines) {
      lines.add("");
    }

    for (final String line : lines) {
      console.println(line);
    }
    lastDisplayLines = lines.size();
  }

  int eventLogSize() {
    return eventLog.size();
  }

  private List<SessionInfo> deduplicateByService(final List<SessionInfo> entries) {
    final Map<String, SessionInfo> latest = new LinkedHashMap<>();
    for (final SessionInfo e : entries) {
      latest.put(e.service(), e);
    }
    return new ArrayList<>(latest.values());
  }
}
