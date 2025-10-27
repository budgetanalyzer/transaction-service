package com.bleurubin.budgetanalyzer.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bleurubin.budgetanalyzer.domain.Transaction;
import com.bleurubin.budgetanalyzer.domain.TransactionType;

public class TransactionUtils {

  public static Map<String, BigDecimal> getMonthlyDebitTotals(List<Transaction> transactions) {
    DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM");

    return transactions.stream()
        // Sort by date descending
        .sorted(Comparator.comparing(Transaction::getDate).reversed())
        // Filter only DEBIT transactions
        .filter(t -> t.getType() == TransactionType.DEBIT)
        // Group by month-year and sum amounts
        .collect(
            Collectors.groupingBy(
                t -> t.getDate().format(monthFormatter),
                Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
  }

  public static BigDecimal averageMonthlyTotal(Map<String, BigDecimal> monthlyTotals) {
    if (monthlyTotals.isEmpty()) {
      return BigDecimal.ZERO;
    }

    // Sum all totals
    var sum = monthlyTotals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

    // Divide by number of months
    var count = monthlyTotals.size();

    return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
  }
}
