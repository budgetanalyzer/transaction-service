package org.budgetanalyzer.transaction.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for preview import token signing and expiration. */
@ConfigurationProperties(prefix = "budgetanalyzer.transaction.preview-import-token")
public record PreviewImportTokenProperties(String signingSecret, Duration ttl) {

  /** Creates validated preview import token configuration. */
  public PreviewImportTokenProperties {
    if (signingSecret == null || signingSecret.isBlank()) {
      throw new IllegalArgumentException("Preview import token signing secret must be configured.");
    }
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Preview import token TTL must be positive.");
    }
  }
}
