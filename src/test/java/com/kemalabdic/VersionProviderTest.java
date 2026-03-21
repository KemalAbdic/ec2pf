package com.kemalabdic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import picocli.CommandLine.IVersionProvider;

class VersionProviderTest {

  private static IVersionProvider newProvider() throws Exception {
    Constructor<PortForwardCommand.VersionProvider> ctor =
      PortForwardCommand.VersionProvider.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    return ctor.newInstance();
  }

  @Test
  void versionOutputContainsJavaWhenNotNative() throws Exception {
    String[] version = newProvider().getVersion();
    assertEquals(1, version.length);
    assertTrue(version[0].startsWith("ec2pf "));
    assertTrue(version[0].contains("Java "));
  }

  @Test
  void versionOutputContainsOsInfo() throws Exception {
    String[] version = newProvider().getVersion();
    String osName = System.getProperty("os.name");
    assertTrue(version[0].contains(osName));
  }
}
