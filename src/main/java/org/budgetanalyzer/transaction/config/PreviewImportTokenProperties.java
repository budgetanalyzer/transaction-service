package org.budgetanalyzer.transaction.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for preview import token encryption and expiration. */
@ConfigurationProperties(prefix = "budgetanalyzer.transaction.preview-import-token")
public record PreviewImportTokenProperties(String encryptionSecret, Duration ttl) {

  /** Creates validated preview import token configuration. */
  public PreviewImportTokenProperties {
    if (encryptionSecret == null || encryptionSecret.isBlank()) {
      throw new IllegalArgumentException(
          "Preview import token encryption secret must be configured.");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Preview import token TTL must be positive.");
    }
  }
}
