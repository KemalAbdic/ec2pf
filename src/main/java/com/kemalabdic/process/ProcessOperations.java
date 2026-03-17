package com.kemalabdic.process;

import java.util.Optional;

public interface ProcessOperations {

  boolean isPortInUse(int port);

  boolean isProcessAlive(long pid);

  Optional<String> getProcessCommand(long pid);

  KillResult killIfExpected(long pid, int localPort, String instanceId);

  boolean killProcessTree(long pid);

  boolean killProcessOnPort(int port);

  Optional<Long> findPidOnPort(int port);

  enum KillResult {
    KILLED, KILLED_LIMITED, SKIPPED_MISMATCH, FAILED
  }
}
