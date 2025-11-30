package org.budgetanalyzer.transaction.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.budgetanalyzer.core.logging.SafeLogger;
import org.budgetanalyzer.service.config.HttpLoggingProperties;

@Component
public class TransactionServiceStartupConfig {

  private static final Logger log = LoggerFactory.getLogger(TransactionServiceStartupConfig.class);

  private final TransactionServiceProperties transactionServiceProperties;
  private final HttpLoggingProperties httpLoggingProperties;

  public TransactionServiceStartupConfig(
      TransactionServiceProperties transactionServiceProperties,
      HttpLoggingProperties httpLoggingProperties) {
    this.transactionServiceProperties = transactionServiceProperties;
    this.httpLoggingProperties = httpLoggingProperties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    logConfiguration();
  }

  private void logConfiguration() {
    log.info(
        "Transaction Service Configuration:\n{}", SafeLogger.toJson(transactionServiceProperties));
    log.info("Http Logging Configuration:\n{}", SafeLogger.toJson(httpLoggingProperties));
  }
}
