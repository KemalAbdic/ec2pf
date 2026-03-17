package com.kemalabdic.aws;

import com.kemalabdic.util.ConsoleOutput;
import com.kemalabdic.util.PlatformUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

@ApplicationScoped
public class AwsErrorReporter {

  private final ConsoleOutput console;
  @Nullable
  private volatile String lastCredentialMessage;

  @Inject
  public AwsErrorReporter(final ConsoleOutput console) {
    this.console = console;
  }

  private static boolean isCredentialError(final String lower) {
    return lower.contains("expired") || lower.contains("expiredtoken")
      || lower.contains("token has expired") || lower.contains("authfailure")
      || lower.contains("not able to validate the provided access credentials")
      || lower.contains("unable to locate credentials") || lower.contains("nocredentialsproviders")
      || (lower.contains("invalid") && lower.contains("token"));
  }

  private static String classifyCredentialMessage(final String lower, final String profile) {
    if (lower.contains("expired") || lower.contains("expiredtoken") || lower.contains("token has expired")) {
      return "expired for profile '%s'".formatted(profile);
    }
    if (lower.contains("authfailure") || lower.contains("not able to validate the provided access credentials")) {
      return "could not validate for profile '%s'".formatted(profile);
    }
    if (lower.contains("unable to locate credentials") || lower.contains("nocredentialsproviders")) {
      return "not found for profile '%s'".formatted(profile);
    }
    return "invalid token for profile '%s'".formatted(profile);
  }

  public void reset() {
    lastCredentialMessage = null;
  }

  public void reportAwsError(final String output, final String profile) {
    final String lower = output.toLowerCase();

    if (reportCredentialError(lower, profile)) {
      return;
    }

    if (lower.contains("could not connect") || lower.contains("connect timeout")) {
      console.error("Network", "cannot reach AWS, check your connection");
    } else if (!output.isBlank()) {
      console.error("AWS CLI", output.trim());
    } else {
      console.error("AWS CLI", "failed (no output), verify profile '%s'".formatted(profile));
    }
  }

  private synchronized boolean reportCredentialError(final String lower, final String profile) {
    if (!isCredentialError(lower)) {
      return false;
    }
    final String message = classifyCredentialMessage(lower, profile);
    if (message.equals(lastCredentialMessage)) {
      return true;
    }
    console.error("Credentials", message);
    credentialHint(profile);
    lastCredentialMessage = message;
    return true;
  }

  private void credentialHint(final String profile) {
    console.warn("Hint", "SSO: aws sso login --profile %s".formatted(profile));
    final String credPath = PlatformUtils.isWindows() ? "%USERPROFILE%\\.aws\\credentials" : "~/.aws/credentials";
    console.warn("Hint", "Keys: check %s or refresh temporary credentials".formatted(credPath));
  }
}
