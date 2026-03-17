package com.kemalabdic.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.PlatformUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

class AwsErrorReporterTest {

  private AwsErrorReporter reporter;
  private ByteArrayOutputStream capturedOut;

  @BeforeEach
  void setUp() {
    capturedOut = new ByteArrayOutputStream();
    final ConsoleOutput console = new ConsoleOutput(new PrintStream(capturedOut), false);
    reporter = new AwsErrorReporter(console);
  }

  @AfterEach
  void tearDown() {
    PlatformUtils.setWindowsOverride(null);
  }

  private String output() {
    return capturedOut.toString();
  }

  private void resetCapture() {
    capturedOut.reset();
  }

  @Test
  void expiredTokenReportsCredentialError() {
    // given
    // when
    reporter.reportAwsError("ExpiredToken: The security token included in the request is expired", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("expired"), "Should mention expired, got: " + out);
    assertTrue(out.contains("dev"), "Should mention profile, got: " + out);
  }

  @Test
  void duplicateCredentialErrorIsSuppressed() {
    // given
    reporter.reportAwsError("ExpiredToken: token expired", "dev");
    resetCapture();

    // when
    reporter.reportAwsError("ExpiredToken: token expired", "dev");

    // then
    assertEquals("", output(), "Duplicate credential error should be suppressed");
  }

  @Test
  void differentCredentialErrorIsNotSuppressed() {
    // given
    reporter.reportAwsError("ExpiredToken: token expired", "dev");
    resetCapture();

    // when
    reporter.reportAwsError("Unable to locate credentials", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("not found"), "Different credential error should produce output, got: " + out);
  }

  @Test
  void resetAllowsSameErrorToBeReportedAgain() {
    // given
    reporter.reportAwsError("ExpiredToken: token expired", "dev");
    resetCapture();
    reporter.reset();

    // when
    reporter.reportAwsError("ExpiredToken: token expired", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("expired"), "After reset, same error should be reported again, got: " + out);
  }

  @Test
  void networkErrorReportsConnectivity() {
    // given
    // when
    reporter.reportAwsError("Could not connect to the endpoint URL", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("cannot reach AWS"), "Should report network error, got: " + out);
  }

  @Test
  void connectTimeoutReportsConnectivity() {
    // given
    // when
    reporter.reportAwsError("Connect timeout on endpoint URL", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("cannot reach AWS"), "Should report network error, got: " + out);
  }

  @Test
  void genericErrorReportsAwsCli() {
    // given
    // when
    reporter.reportAwsError("Some unknown AWS error", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("Some unknown AWS error"), "Should pass through generic error, got: " + out);
  }

  @Test
  void blankOutputReportsFallback() {
    // given
    // when
    reporter.reportAwsError("   ", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("failed (no output)"), "Blank output should report fallback, got: " + out);
  }

  @Test
  void authFailureReportsValidationError() {
    // given
    // when
    reporter.reportAwsError("AuthFailure: credentials are not valid", "prod");
    final String out = output();

    // then
    assertTrue(out.contains("could not validate"), "Should report auth failure, got: " + out);
    assertTrue(out.contains("prod"), "Should mention profile, got: " + out);
  }

  @Test
  void noCredentialProvidersReportsNotFound() {
    // given
    // when
    reporter.reportAwsError("NoCredentialsProviders: no valid providers", "staging");
    final String out = output();

    // then
    assertTrue(out.contains("not found"), "Should report credentials not found, got: " + out);
  }

  @Test
  void invalidTokenReportsInvalidToken() {
    // given
    // when
    reporter.reportAwsError("Invalid token: bad", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("invalid token"), "Should report invalid token, got: " + out);
  }

  @Test
  void credentialErrorShowsHints() {
    // given
    // when
    reporter.reportAwsError("ExpiredToken: expired", "my-profile");
    final String out = output();

    // then
    assertTrue(out.contains("aws sso login"), "Should show SSO hint, got: " + out);
    assertTrue(out.contains("my-profile"), "Should show profile in hint, got: " + out);
    assertTrue(out.contains("credentials"), "Should show credentials hint, got: " + out);
  }

  @Test
  void tokenHasExpiredReportsCredentialError() {
    // given
    // when
    reporter.reportAwsError("token has expired and needs refresh", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("expired"), "Should mention expired, got: " + out);
  }

  @Test
  void notAbleToValidateReportsAuthFailure() {
    // given
    // when
    reporter.reportAwsError("not able to validate the provided access credentials", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("could not validate"), "Should report validation error, got: " + out);
  }

  @Test
  void expiredTokenVariantViaContainsExpiredToken() {
    // when
    reporter.reportAwsError("ExpiredTokenException: session has ended", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("expired"), "Should match expiredtoken condition, got: " + out);
  }

  @Test
  void invalidTokenWithoutExpiredHitsClassifyFallback() {
    // when
    reporter.reportAwsError("The provided token is invalid", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("invalid token"), "Should hit classify fallback for invalid token, got: " + out);
  }

  @Test
  void onlyExpiredTokenWithoutOtherKeywords() {
    // when
    reporter.reportAwsError("The SSO token has expired, please re-authenticate", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("expired"), "Should report expired via 'token has expired' branch, got: " + out);
  }

  @Test
  void credentialHintShowsWindowsPath() {
    // given
    PlatformUtils.setWindowsOverride(true);

    // when
    reporter.reportAwsError("ExpiredToken: expired", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("%USERPROFILE%"), "Should show Windows credentials path, got: " + out);
  }

  @Test
  void credentialHintShowsUnixPath() {
    // given
    PlatformUtils.setWindowsOverride(false);

    // when
    reporter.reportAwsError("ExpiredToken: expired", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("~/.aws/credentials"), "Should show Unix credentials path, got: " + out);
  }

  @Test
  void nonCredentialErrorIsNotClassifiedAsCredential() {
    // given
    // when
    reporter.reportAwsError("AccessDenied: not authorized", "dev");
    final String out = output();

    // then
    assertTrue(out.contains("AccessDenied"), "Should pass through as generic AWS error, got: " + out);
  }
}
