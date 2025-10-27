package com.bleurubin.budgetanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;

@SpringBootApplication
@EnableConfigurationProperties(BudgetAnalyzerProperties.class)
public class BudgetAnalyzerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BudgetAnalyzerApplication.class, args);
  }
}
