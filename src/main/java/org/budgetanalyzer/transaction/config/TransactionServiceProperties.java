package org.budgetanalyzer.transaction.config;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "budget-analyzer.transaction-service")
@Validated
public record TransactionServiceProperties(@Valid Map<String, CsvConfig> csvConfigMap) {}
