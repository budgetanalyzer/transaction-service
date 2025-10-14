package com.bleurubin.budgetanalyzer.service;

import com.bleurubin.budgetanalyzer.config.BankConfig;
import com.bleurubin.budgetanalyzer.domain.Transaction;

import java.math.BigDecimal;
import java.util.Map;

public class GenericTransactionMapper {

    private final Map<String, BankConfig> bankConfigMap;

    public GenericTransactionMapper(Map<String, BankConfig> bankConfigs) {
        this.bankConfigMap = bankConfigs;
    }

    public Transaction map(String bankCode, Map<String, String> csvRow) {
        var bankConfig = bankConfigMap.get(bankCode);
        printBanks();

        if (bankConfig == null) {
            throw new IllegalArgumentException("No bankConfig found for bank " + bankCode);
        }

        var name = bankConfig.name();
        var accountIdHeader = bankConfig.accountIdHeader();
        var amountHeader = bankConfig.amountHeader();
        var dateHeader = bankConfig.dateHeader();
        var descriptionHeader = bankConfig.descriptionHeader();
        var typeHeader = bankConfig.typeHeader();
        var currencyIsoCode = bankConfig.currencyIsoCode();

        var rv = new Transaction();
        rv.setAccountId(csvRow.get(accountIdHeader));

        var amountSanitized = sanitizeNumberField(csvRow.get(amountHeader));
        var amount = new BigDecimal(amountSanitized);
        rv.setAmount(amount);

        rv.setDescription(csvRow.get(descriptionHeader));
        rv.setCurrencyIsoCode(currencyIsoCode);

        return rv;
    }

    private String sanitizeNumberField(String val) {
        return val.replaceAll("[$,]", "");
    }

    public void printBanks() {
        bankConfigMap.forEach((code, bank) -> {
            System.out.printf("Code: %s, Name: %s, Currency: %s%n",
                    code, bank.name(), bank.currencyIsoCode());
        });
    }
}

