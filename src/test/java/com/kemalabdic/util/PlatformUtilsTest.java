package com.kemalabdic.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlatformUtilsTest {

  @AfterEach
  void tearDown() {
    PlatformUtils.setWindowsOverride(null);
  }

  @Test
  void isWindowsMatchesPlatformByDefault() {
    // given
    final boolean expected = System.getProperty("os.name", "").toLowerCase().contains("win");

    // when / then
    assertEquals(expected, PlatformUtils.isWindows());
  }

  @Test
  void isWindowsReturnsTrueWhenOverridden() {
    // given
    PlatformUtils.setWindowsOverride(true);

    // when / then
    assertTrue(PlatformUtils.isWindows());
  }

  @Test
  void isWindowsReturnsFalseWhenOverridden() {
    // given
    PlatformUtils.setWindowsOverride(false);

    // when / then
    assertFalse(PlatformUtils.isWindows());
  }
}
