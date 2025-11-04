package com.bleurubin.budgetanalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bleurubin.core.logging.SafeLogger;
import com.bleurubin.service.http.HttpLoggingProperties;

@Component
public class BudgetAnalyzerStartupConfig {

  private static final Logger log = LoggerFactory.getLogger(BudgetAnalyzerStartupConfig.class);

  private final BudgetAnalyzerProperties budgetAnalyzerProperties;
  private final HttpLoggingProperties httpLoggingProperties;

  public BudgetAnalyzerStartupConfig(
      BudgetAnalyzerProperties budgetAnalyzerProperties,
      HttpLoggingProperties httpLoggingProperties) {
    this.budgetAnalyzerProperties = budgetAnalyzerProperties;
    this.httpLoggingProperties = httpLoggingProperties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    logConfiguration();
  }

  private void logConfiguration() {
    log.info("Budget Analyzer API Configuration:\n{}", SafeLogger.toJson(budgetAnalyzerProperties));
    log.info("Http Logging Configuration:\n{}", SafeLogger.toJson(httpLoggingProperties));
  }
}
