package com.bleurubin.budgetanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import com.bleurubin.budgetanalyzer.config.BudgetAnalyzerProperties;
import com.bleurubin.service.api.DefaultApiExceptionHandler;

@SpringBootApplication
@EnableConfigurationProperties(BudgetAnalyzerProperties.class)
@Import(DefaultApiExceptionHandler.class)
public class BudgetAnalyzerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BudgetAnalyzerApplication.class, args);
  }
}
