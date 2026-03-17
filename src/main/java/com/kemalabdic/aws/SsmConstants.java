package com.kemalabdic.aws;

public final class SsmConstants {

  public static final String CMD_AWS = "aws";
  public static final String CMD_EC2 = "ec2";
  public static final String CMD_SSM = "ssm";
  public static final String CMD_DESCRIBE_INSTANCES = "describe-instances";
  public static final String CMD_START_SESSION = "start-session";
  public static final String DOCUMENT_PORT_FORWARDING = "AWS-StartPortForwardingSession";

  private SsmConstants() { // not instantiated
  }

  public static String localPortParam(final int localPort) {
    return "localPortNumber=[\"" + localPort + "\"]";
  }
}
