package com.kemalabdic.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessagesTest {

  @Test
  void sessionCountFormatsCorrectly() {
    // given
    final int count = 5;

    // when
    final String result = Messages.sessionCount(count);

    // then
    assertEquals("5 session(s)", result);
  }
}
