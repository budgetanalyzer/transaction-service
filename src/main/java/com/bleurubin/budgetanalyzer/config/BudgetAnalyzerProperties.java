package com.bleurubin.budgetanalyzer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "budget-analyzer")
public class BudgetAnalyzerProperties {

    private Map<String, BankConfig> bankConfigMap;

    public Map<String, BankConfig> getBankConfigMap() {
        return bankConfigMap;
    }

    public void setBankConfigMap(Map<String, BankConfig> bankConfigMap) {
        this.bankConfigMap = bankConfigMap;
    }
}

