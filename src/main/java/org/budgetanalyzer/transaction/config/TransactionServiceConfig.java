package org.budgetanalyzer.transaction.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TransactionServiceProperties.class)
@ComponentScan({"org.budgetanalyzer.core.csv"})
public class TransactionServiceConfig {}
