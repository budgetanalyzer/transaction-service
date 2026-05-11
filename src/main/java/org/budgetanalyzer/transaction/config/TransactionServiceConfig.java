package org.budgetanalyzer.transaction.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Transaction service configuration.
 *
 * <p>Note: service-common beans (including CSV parsing) are auto-configured via Spring Boot
 * autoconfiguration mechanism. Explicit @ComponentScan is NOT required.
 */
@Configuration
@EnableConfigurationProperties(PreviewImportTokenProperties.class)
public class TransactionServiceConfig {

  /** Provides the application clock for time-sensitive service logic. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
