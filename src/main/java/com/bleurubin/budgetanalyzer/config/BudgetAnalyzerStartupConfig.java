package com.bleurubin.budgetanalyzer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.bleurubin.core.util.JsonUtils;

@Component
public class BudgetAnalyzerStartupConfig {

  private static final Logger log = LoggerFactory.getLogger(BudgetAnalyzerStartupConfig.class);

  private final BudgetAnalyzerProperties budgetAnalyzerProperties;

  public BudgetAnalyzerStartupConfig(BudgetAnalyzerProperties properties) {
    this.budgetAnalyzerProperties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    logConfiguration();
  }

  private void logConfiguration() {
    log.info("Budget Analyzer API Configuration:\n{}", JsonUtils.toJson(budgetAnalyzerProperties));
  }
}
