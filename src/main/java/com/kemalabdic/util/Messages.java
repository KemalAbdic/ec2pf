package com.kemalabdic.util;

public final class Messages {

  public static final String ERR_CONFIG_NOT_FOUND = "Config file not found: %s";
  public static final String ERR_CONFIG_PARSE = "Config parse error: %s";
  public static final String ERR_PID_FILE_READ = "Failed to read PID file: %s";
  public static final String ERR_PID_FILE_REMOVE = "Failed to remove PID file: %s";
  public static final String ERR_PID_FILE_CREATE = "Failed to create PID file: %s";
  public static final String ERR_PID_FILE_WRITE = "Failed to write PID file entry: ";
  public static final String ERR_PID_FILES_FIND = "Failed to find PID files: %s";
  public static final String MSG_NO_ACTIVE_SESSIONS = "No active sessions found";
  public static final String MSG_NO_ACTIVE_SESSIONS_FOR = "No active sessions found for %s";
  public static final String MSG_SESSION_COUNT = "%d session(s)";
  public static final String MSG_SERVICES_DEFINED = "%d defined";

  private Messages() { // not instantiated
  }

  public static String sessionCount(int count) {
    return MSG_SESSION_COUNT.formatted(count);
  }
}
