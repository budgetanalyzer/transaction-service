package com.bleurubin.budgetanalyzer.config;

public record BankConfig(
        String name,
        String currencyIsoCode,
        String accountIdHeader,
        String amountHeader,
        String dateHeader,
        String descriptionHeader,
        String typeHeader
) {
}