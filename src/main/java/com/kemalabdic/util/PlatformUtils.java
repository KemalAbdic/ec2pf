package com.kemalabdic.util;

import org.jspecify.annotations.Nullable;

public final class PlatformUtils {

  private static volatile @Nullable Boolean windowsOverride;

  private PlatformUtils() {
    // not instantiated
  }

  public static boolean isWindows() {
    Boolean override = windowsOverride;
    return override != null ? override : DetectedHolder.IS_WINDOWS;
  }

  public static void setWindowsOverride(final @Nullable Boolean value) {
    windowsOverride = value;
  }

  private static final class DetectedHolder {
    static final boolean IS_WINDOWS =
      System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
