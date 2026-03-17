package com.kemalabdic.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

class ConsoleOutputTest {

  private ByteArrayOutputStream capturedOut;
  private ConsoleOutput console;

  @BeforeEach
  void setUp() {
    capturedOut = new ByteArrayOutputStream();
    console = new ConsoleOutput(new PrintStream(capturedOut), false);
  }

  private String output() {
    return capturedOut.toString();
  }

  @Test
  void infoWithVerbAndMessagePrintsFormattedOutput() {
    // given
    final String verb = "Loading";
    final String message = "config file";

    // when
    console.info(verb, message);

    // then
    final String out = output();
    assertTrue(out.contains("Loading"), "Should contain verb, got: " + out);
    assertTrue(out.contains("config file"), "Should contain message, got: " + out);
  }

  @Test
  void successWithVerbAndMessagePrintsFormattedOutput() {
    // given
    final String verb = "Done";
    final String message = "all services started";

    // when
    console.success(verb, message);

    // then
    final String out = output();
    assertTrue(out.contains("Done"), "Should contain verb, got: " + out);
    assertTrue(out.contains("all services started"), "Should contain message, got: " + out);
  }

  @Test
  void warnWithVerbAndMessagePrintsFormattedOutput() {
    // given
    final String verb = "Caution";
    final String message = "port conflict";

    // when
    console.warn(verb, message);

    // then
    final String out = output();
    assertTrue(out.contains("Caution"), "Should contain verb, got: " + out);
    assertTrue(out.contains("port conflict"), "Should contain message, got: " + out);
  }

  @Test
  void errorWithVerbAndMessagePrintsFormattedOutput() {
    // given
    final String verb = "Fatal";
    final String message = "connection refused";

    // when
    console.error(verb, message);

    // then
    final String out = output();
    assertTrue(out.contains("Fatal"), "Should contain verb, got: " + out);
    assertTrue(out.contains("connection refused"), "Should contain message, got: " + out);
  }

  @Test
  void skipWithVerbAndMessagePrintsFormattedOutput() {
    // given
    final String verb = "Ignored";
    final String message = "skip=true";

    // when
    console.skip(verb, message);

    // then
    final String out = output();
    assertTrue(out.contains("Ignored"), "Should contain verb, got: " + out);
    assertTrue(out.contains("skip=true"), "Should contain message, got: " + out);
  }

  @Test
  void dryRunWithVerbAndMessagePrintsFormattedOutput() {
    // given
    final String verb = "Simulate";
    final String message = "would start session";

    // when
    console.dryRun(verb, message);

    // then
    final String out = output();
    assertTrue(out.contains("Simulate"), "Should contain verb, got: " + out);
    assertTrue(out.contains("would start session"), "Should contain message, got: " + out);
  }

  @Test
  void infoWithMessageOnlyUsesDefaultVerb() {
    // given
    final String message = "general info";

    // when
    console.info(message);

    // then
    final String out = output();
    assertTrue(out.contains("Info"), "Should contain default verb 'Info', got: " + out);
    assertTrue(out.contains("general info"), "Should contain message, got: " + out);
  }

  @Test
  void successWithMessageOnlyUsesDefaultVerb() {
    // given
    final String message = "completed";

    // when
    console.success(message);

    // then
    final String out = output();
    assertTrue(out.contains("OK"), "Should contain default verb 'OK', got: " + out);
    assertTrue(out.contains("completed"), "Should contain message, got: " + out);
  }

  @Test
  void warnWithMessageOnlyUsesDefaultVerb() {
    // given
    final String message = "potential issue";

    // when
    console.warn(message);

    // then
    final String out = output();
    assertTrue(out.contains("Warning"), "Should contain default verb 'Warning', got: " + out);
    assertTrue(out.contains("potential issue"), "Should contain message, got: " + out);
  }

  @Test
  void errorWithMessageOnlyUsesDefaultVerb() {
    // given
    final String message = "something broke";

    // when
    console.error(message);

    // then
    final String out = output();
    assertTrue(out.contains("Error"), "Should contain default verb 'Error', got: " + out);
    assertTrue(out.contains("something broke"), "Should contain message, got: " + out);
  }

  @Test
  void skipWithMessageOnlyUsesDefaultVerb() {
    // given
    final String message = "not needed";

    // when
    console.skip(message);

    // then
    final String out = output();
    assertTrue(out.contains("Skipped"), "Should contain default verb 'Skipped', got: " + out);
    assertTrue(out.contains("not needed"), "Should contain message, got: " + out);
  }

  @Test
  void dryRunWithMessageOnlyUsesDefaultVerb() {
    // given
    final String message = "would connect";

    // when
    console.dryRun(message);

    // then
    final String out = output();
    assertTrue(out.contains("Dry-run"), "Should contain default verb 'Dry-run', got: " + out);
    assertTrue(out.contains("would connect"), "Should contain message, got: " + out);
  }

  @Test
  void statusLabelReturnsUpForAliveAndDownForDead() {
    // given
    final boolean alive = true;
    final boolean dead = false;

    // when
    final String upLabel = console.statusLabel(alive);
    final String downLabel = console.statusLabel(dead);

    // then
    assertTrue(upLabel.contains("up"), "Alive should contain 'up', got: " + upLabel);
    assertFalse(upLabel.contains("down"), "Alive should not contain 'down', got: " + upLabel);
    assertTrue(downLabel.contains("down"), "Dead should contain 'down', got: " + downLabel);
    assertFalse(downLabel.contains("up"), "Dead should not contain 'up', got: " + downLabel);
  }

  @Test
  void cursorUpWithAnsiEnabledEmitsEscape() {
    // given
    final ConsoleOutput ansiConsole = new ConsoleOutput(new PrintStream(capturedOut), true);

    // when
    ansiConsole.cursorUp(3);

    // then
    final String out = output();
    assertTrue(out.contains("\033[3A"), "Should emit ANSI cursor-up escape, got: " + out);
  }

  @Test
  void cursorUpWithAnsiDisabledEmitsNothing() {
    // when
    console.cursorUp(3);

    // then
    final String out = output();
    assertTrue(out.isEmpty(), "Should emit nothing when ANSI disabled, got: " + out);
  }

  @Test
  void cursorUpWithZeroLinesEmitsNothing() {
    // given
    final ConsoleOutput ansiConsole = new ConsoleOutput(new PrintStream(capturedOut), true);
    final int lines = 0;

    // when
    ansiConsole.cursorUp(lines);

    // then
    final String out = output();
    assertTrue(out.isEmpty(), "Should emit nothing for zero lines, got: " + out);
  }

  @Test
  void clearLineWithAnsiEnabledEmitsEscape() {
    // given
    final ConsoleOutput ansiConsole = new ConsoleOutput(new PrintStream(capturedOut), true);

    // when
    ansiConsole.clearLine();

    // then
    final String out = output();
    assertTrue(out.contains("\033[2K"), "Should emit ANSI clear-line escape, got: " + out);
  }

  @Test
  void clearLineWithAnsiDisabledEmitsNothing() {
    // when
    console.clearLine();

    // then
    final String out = output();
    assertTrue(out.isEmpty(), "Should emit nothing when ANSI disabled, got: " + out);
  }

  @Test
  void summaryPrintsConnectedCount() {
    // when
    console.summary(3, 0, 0);

    // then
    final String out = output();
    assertTrue(out.contains("3 connected"), "Should contain connected count, got: " + out);
    assertTrue(out.contains("Summary"), "Should contain Summary verb, got: " + out);
  }

  @Test
  void summaryIncludesSkippedAndFailedWhenNonZero() {
    // when
    console.summary(2, 1, 1);

    // then
    final String out = output();
    assertTrue(out.contains("2 connected"), "Should contain connected count, got: " + out);
    assertTrue(out.contains("1 skipped"), "Should contain skipped count, got: " + out);
    assertTrue(out.contains("1 failed"), "Should contain failed count, got: " + out);
  }

  @Test
  void summaryOmitsSkippedAndFailedWhenZero() {
    // when
    console.summary(5, 0, 0);

    // then
    final String out = output();
    assertTrue(out.contains("5 connected"), "Should contain connected count, got: " + out);
    assertFalse(out.contains("skipped"), "Should not contain skipped when zero, got: " + out);
    assertFalse(out.contains("failed"), "Should not contain failed when zero, got: " + out);
  }

  @Test
  void printlnOutputsText() {
    // when
    console.println("hello world");

    // then
    final String out = output();
    assertTrue(out.contains("hello world"), "Should contain printed text, got: " + out);
  }
}
