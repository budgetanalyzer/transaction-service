package com.bleurubin.budgetanalyzer;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BudgetAnalyzerProperties.class)
public class BudgetAnalyzerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BudgetAnalyzerApplication.class, args);
  }
}
