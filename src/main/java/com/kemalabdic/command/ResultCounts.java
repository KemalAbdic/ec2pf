package com.kemalabdic.command;

record ResultCounts(int succeeded, int skipped, int failed) {

  ResultCounts add(ResultCounts other) {
    return new ResultCounts(succeeded + other.succeeded, skipped + other.skipped, failed + other.failed);
  }
}
