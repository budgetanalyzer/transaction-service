package com.bleurubin.budgetanalyzer.config;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@ConfigurationProperties(prefix = "budget-analyzer")
@Validated
public record BudgetAnalyzerProperties(@Valid Map<String, CsvConfig> csvConfigMap) {}

