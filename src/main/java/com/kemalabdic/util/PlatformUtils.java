package com.kemalabdic.util;

import java.util.Objects;

public final class PlatformUtils {

  private static final boolean DEFAULT_WINDOWS =
    System.getProperty("os.name", "").toLowerCase().contains("win");

  private static volatile Boolean windowsOverride;

  private PlatformUtils() {
    // not instantiated
  }

  public static boolean isWindows() {
    return Objects.requireNonNullElse(windowsOverride, DEFAULT_WINDOWS);
  }

  public static void setWindowsOverride(final Boolean value) {
    windowsOverride = value;
  }
}
