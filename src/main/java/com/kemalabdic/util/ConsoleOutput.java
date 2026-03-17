package com.kemalabdic.util;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import picocli.CommandLine.Help.Ansi;

@ApplicationScoped
public class ConsoleOutput {

  private static final int VERB_WIDTH = 12;
  private final PrintStream out;
  private final boolean ansiEnabled;

  public ConsoleOutput() {
    this(
      System.out, Ansi.AUTO.enabled());
  }

  public ConsoleOutput(final PrintStream out, final boolean ansiEnabled) {
    this.out = out;
    this.ansiEnabled = ansiEnabled;
  }


  private void print(final String style, final String verb, final String message) {
    final String padded = "%" + VERB_WIDTH + "s";
    out.println(Ansi.AUTO.string(
      "@|" + style + " " + padded.formatted(verb) + "|@ " + message));
  }


  public void info(final String verb, final String message) {
    print("bold,cyan", verb, message);
  }

  public void success(final String verb, final String message) {
    print("bold,green", verb, message);
  }

  public void warn(final String verb, final String message) {
    print("bold,yellow", verb, message);
  }

  public void error(final String verb, final String message) {
    print("bold,red", verb, message);
  }

  public void skip(final String verb, final String message) {
    print("faint", verb, message);
  }

  public void dryRun(final String verb, final String message) {
    print("bold,magenta", verb, message);
  }


  public void info(final String message) {
    info("Info", message);
  }

  public void success(final String message) {
    success("OK", message);
  }

  public void warn(final String message) {
    warn("Warning", message);
  }

  public void error(final String message) {
    error("Error", message);
  }

  public void skip(final String message) {
    skip("Skipped", message);
  }

  public void dryRun(final String message) {
    dryRun("Dry-run", message);
  }


  public String statusLabel(final boolean alive) {
    return Ansi.AUTO.string(alive
      ? "@|bold,green up|@"
      : "@|bold,red down|@");
  }


  public void cursorUp(final int lines) {
    if (lines > 0 && ansiEnabled) {
      out.print("\033[" + lines + "A");
    }
  }

  public void clearLine() {
    if (ansiEnabled) {
      out.print("\033[2K\r");
    }
  }

  public void summary(final int succeeded, final int skipped, final int failed) {
    final StringBuilder sb = new StringBuilder();
    sb.append(Ansi.AUTO.string("@|bold,green %d connected|@".formatted(succeeded)));
    if (skipped > 0) {
      sb.append(Ansi.AUTO.string("  @|bold,yellow %d skipped|@".formatted(skipped)));
    }
    if (failed > 0) {
      sb.append(Ansi.AUTO.string("  @|bold,red %d failed|@".formatted(failed)));
    }
    print("bold", "Summary", sb.toString());
  }

  public void blank() {
    out.println();
  }

  public void println(final String text) {
    out.println(text);
  }
}
