package com.bleurubin.budgetanalyzer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BudgetAnalyzerProperties.class)
@ComponentScan({"com.bleurubin.core.csv", "com.bleurubin.service.api"})
public class BudgetAnalyzerConfig {}
