package org.budgetanalyzer.transaction.repository.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;

/**
 * Integration tests for {@link TransactionSpecifications}.
 *
 * <p>Tests the specification builder against a real PostgreSQL database using TestContainers to
 * ensure LIKE pattern escaping, multi-word OR filtering, and all filter combinations work
 * correctly.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionSpecificationsIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private TransactionRepository transactionRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void setUp() {
    transactionRepository.deleteAll();
  }

  // ==================== Description Filter Tests ====================

  @Test
  void withFilter_descriptionSingleWord_matchesContainingWord() {
    // Given: transactions with various descriptions
    transactionRepository.save(createTransaction("Amazon Prime Video", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Amazon Web Services", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Netflix Subscription", BigDecimal.TEN));

    // When: filter by single word "amazon"
    var spec = TransactionSpecifications.withFilter(filterByDescription("amazon"));
    var results = transactionRepository.findAll(spec);

    // Then: only Amazon transactions are returned
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getDescription)
        .allMatch(desc -> desc.toLowerCase().contains("amazon"));
  }

  @Test
  void withFilter_descriptionMultipleWords_matchesAnyWord() {
    // Given: transactions with various descriptions
    transactionRepository.save(createTransaction("Amazon Prime Video", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Whole Foods Market", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Target Store", BigDecimal.TEN));

    // When: filter by multiple words "amazon target"
    var spec = TransactionSpecifications.withFilter(filterByDescription("amazon target"));
    var results = transactionRepository.findAll(spec);

    // Then: transactions containing "amazon" OR "target" are returned
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getDescription)
        .containsExactlyInAnyOrder("Amazon Prime Video", "Target Store");
  }

  @Test
  void withFilter_descriptionWithPercent_escapesWildcard() {
    // Given: transactions with percent signs
    transactionRepository.save(createTransaction("100% Bonus", BigDecimal.TEN));
    transactionRepository.save(createTransaction("1000 Points", BigDecimal.TEN));
    transactionRepository.save(createTransaction("10000 Reward", BigDecimal.TEN));

    // When: filter by "100%"
    var spec = TransactionSpecifications.withFilter(filterByDescription("100%"));
    var results = transactionRepository.findAll(spec);

    // Then: only literal "100%" matches (% is escaped, doesn't act as wildcard)
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getDescription()).isEqualTo("100% Bonus");
  }

  @Test
  void withFilter_descriptionWithUnderscore_escapesWildcard() {
    // Given: transactions with underscores
    transactionRepository.save(createTransaction("test_case payment", BigDecimal.TEN));
    transactionRepository.save(createTransaction("testAcase payment", BigDecimal.TEN));
    transactionRepository.save(createTransaction("test-case payment", BigDecimal.TEN));

    // When: filter by "test_case"
    var spec = TransactionSpecifications.withFilter(filterByDescription("test_case"));
    var results = transactionRepository.findAll(spec);

    // Then: only literal "test_case" matches (_ is escaped, doesn't act as single-char wildcard)
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getDescription()).contains("test_case");
  }

  @Test
  void withFilter_descriptionCaseInsensitive_matchesRegardlessOfCase() {
    // Given: transactions with mixed case
    transactionRepository.save(createTransaction("STARBUCKS COFFEE", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Starbucks Coffee", BigDecimal.TEN));
    transactionRepository.save(createTransaction("starbucks coffee", BigDecimal.TEN));

    // When: filter by lowercase "starbucks"
    var spec = TransactionSpecifications.withFilter(filterByDescription("starbucks"));
    var results = transactionRepository.findAll(spec);

    // Then: all variations are matched (case-insensitive)
    assertThat(results).hasSize(3);
  }

  @Test
  void withFilter_descriptionMultipleSpaces_treatsAsMultipleWords() {
    // Given: transactions
    transactionRepository.save(createTransaction("Amazon Prime", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Target Store", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Walmart", BigDecimal.TEN));

    // When: filter with multiple spaces "amazon    target"
    var spec = TransactionSpecifications.withFilter(filterByDescription("amazon    target"));
    var results = transactionRepository.findAll(spec);

    // Then: matches "amazon" OR "target"
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getDescription)
        .containsExactlyInAnyOrder("Amazon Prime", "Target Store");
  }

  @Test
  void withFilter_descriptionBlank_returnsAllTransactions() {
    // Given: transactions exist
    transactionRepository.save(createTransaction("Transaction 1", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Transaction 2", BigDecimal.TEN));

    // When: filter with blank description
    var spec = TransactionSpecifications.withFilter(filterByDescription("   "));
    var results = transactionRepository.findAll(spec);

    // Then: all transactions are returned (blank filter ignored)
    assertThat(results).hasSize(2);
  }

  // ==================== Account ID Filter Tests ====================

  @Test
  void withFilter_accountIdSingleWord_matchesContainingWord() {
    // Given: transactions with different account IDs
    transactionRepository.save(
        createTransactionWithAccount("Transaction 1", "acc_123456", BigDecimal.TEN));
    transactionRepository.save(
        createTransactionWithAccount("Transaction 2", "acc_789012", BigDecimal.TEN));
    transactionRepository.save(
        createTransactionWithAccount("Transaction 3", "xyz_111111", BigDecimal.TEN));

    // When: filter by "acc"
    var spec = TransactionSpecifications.withFilter(filterByAccountId("acc"));
    var results = transactionRepository.findAll(spec);

    // Then: only accounts containing "acc" are returned
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getAccountId)
        .allMatch(id -> id.toLowerCase().contains("acc"));
  }

  @Test
  void withFilter_accountIdMultipleWords_matchesAnyWord() {
    // Given: transactions with different account IDs
    transactionRepository.save(
        createTransactionWithAccount("Transaction 1", "checking_account", BigDecimal.TEN));
    transactionRepository.save(
        createTransactionWithAccount("Transaction 2", "savings_account", BigDecimal.TEN));
    transactionRepository.save(
        createTransactionWithAccount("Transaction 3", "credit_card", BigDecimal.TEN));

    // When: filter by "checking credit"
    var spec = TransactionSpecifications.withFilter(filterByAccountId("checking credit"));
    var results = transactionRepository.findAll(spec);

    // Then: matches "checking" OR "credit"
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getAccountId)
        .containsExactlyInAnyOrder("checking_account", "credit_card");
  }

  // ==================== Bank Name Filter Tests ====================

  @Test
  void withFilter_bankNameSingleWord_matchesContainingWord() {
    // Given: transactions from different banks
    transactionRepository.save(createTransactionWithBank("Transaction 1", "Chase Bank"));
    transactionRepository.save(createTransactionWithBank("Transaction 2", "Chase Credit Union"));
    transactionRepository.save(createTransactionWithBank("Transaction 3", "Wells Fargo"));

    // When: filter by "chase"
    var spec = TransactionSpecifications.withFilter(filterByBankName("chase"));
    var results = transactionRepository.findAll(spec);

    // Then: only Chase banks are returned
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getBankName)
        .allMatch(name -> name.toLowerCase().contains("chase"));
  }

  @Test
  void withFilter_bankNameMultipleWords_matchesAnyWord() {
    // Given: transactions from different banks
    transactionRepository.save(createTransactionWithBank("Transaction 1", "Wells Fargo"));
    transactionRepository.save(createTransactionWithBank("Transaction 2", "Bank of America"));
    transactionRepository.save(createTransactionWithBank("Transaction 3", "Chase Bank"));

    // When: filter by "wells chase"
    var spec = TransactionSpecifications.withFilter(filterByBankName("wells chase"));
    var results = transactionRepository.findAll(spec);

    // Then: matches "wells" OR "chase"
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getBankName)
        .containsExactlyInAnyOrder("Wells Fargo", "Chase Bank");
  }

  // ==================== Currency Code Filter Tests ====================

  @Test
  void withFilter_currencyIsoCode_exactMatchCaseInsensitive() {
    // Given: transactions with different currencies
    transactionRepository.save(createTransactionWithCurrency("Transaction 1", "USD"));
    transactionRepository.save(createTransactionWithCurrency("Transaction 2", "EUR"));
    transactionRepository.save(createTransactionWithCurrency("Transaction 3", "GBP"));

    // When: filter by "usd" (lowercase)
    var spec = TransactionSpecifications.withFilter(filterByCurrency("usd"));
    var results = transactionRepository.findAll(spec);

    // Then: only USD transactions are returned
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getCurrencyIsoCode()).isEqualTo("USD");
  }

  // ==================== Transaction Type Filter Tests ====================

  @Test
  void withFilter_type_matchesExactType() {
    // Given: transactions with different types
    transactionRepository.save(createTransactionWithType("Debit 1", TransactionType.DEBIT));
    transactionRepository.save(createTransactionWithType("Debit 2", TransactionType.DEBIT));
    transactionRepository.save(createTransactionWithType("Credit 1", TransactionType.CREDIT));

    // When: filter by DEBIT type
    var spec = TransactionSpecifications.withFilter(filterByType(TransactionType.DEBIT));
    var results = transactionRepository.findAll(spec);

    // Then: only DEBIT transactions are returned
    assertThat(results).hasSize(2);
    assertThat(results).extracting(Transaction::getType).containsOnly(TransactionType.DEBIT);
  }

  // ==================== Date Range Filter Tests ====================

  @Test
  void withFilter_dateRange_matchesWithinRange() {
    // Given: transactions on different dates
    var jan1 = LocalDate.of(2025, 1, 1);
    var jan15 = LocalDate.of(2025, 1, 15);
    var jan31 = LocalDate.of(2025, 1, 31);
    var feb15 = LocalDate.of(2025, 2, 15);

    transactionRepository.save(createTransactionWithDate("Transaction 1", jan1));
    transactionRepository.save(createTransactionWithDate("Transaction 2", jan15));
    transactionRepository.save(createTransactionWithDate("Transaction 3", jan31));
    transactionRepository.save(createTransactionWithDate("Transaction 4", feb15));

    // When: filter by date range (Jan 10 - Jan 25)
    var spec =
        TransactionSpecifications.withFilter(
            filterByDateRange(LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 25)));
    var results = transactionRepository.findAll(spec);

    // Then: only transactions within range are returned
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getDescription()).isEqualTo("Transaction 2");
  }

  // ==================== Amount Range Filter Tests ====================

  @Test
  void withFilter_amountRange_matchesWithinRange() {
    // Given: transactions with different amounts
    transactionRepository.save(createTransaction("Transaction 1", BigDecimal.valueOf(10.00)));
    transactionRepository.save(createTransaction("Transaction 2", BigDecimal.valueOf(50.00)));
    transactionRepository.save(createTransaction("Transaction 3", BigDecimal.valueOf(100.00)));
    transactionRepository.save(createTransaction("Transaction 4", BigDecimal.valueOf(200.00)));

    // When: filter by amount range (25.00 - 150.00)
    var spec =
        TransactionSpecifications.withFilter(
            filterByAmountRange(BigDecimal.valueOf(25.00), BigDecimal.valueOf(150.00)));
    var results = transactionRepository.findAll(spec);

    // Then: only transactions within range are returned
    assertThat(results).hasSize(2);
    assertThat(results)
        .extracting(Transaction::getDescription)
        .containsExactlyInAnyOrder("Transaction 2", "Transaction 3");
  }

  // ==================== Combined Filter Tests ====================

  @Test
  void withFilter_multipleFilters_appliesAllWithAnd() {
    // Given: various transactions
    transactionRepository.save(
        createComplexTransaction("Amazon Prime", "acc_123", "Chase", BigDecimal.valueOf(15.99)));
    transactionRepository.save(
        createComplexTransaction("Amazon AWS", "acc_456", "Chase", BigDecimal.valueOf(50.00)));
    transactionRepository.save(
        createComplexTransaction("Target Store", "acc_123", "Chase", BigDecimal.valueOf(25.00)));
    transactionRepository.save(
        createComplexTransaction("Walmart", "acc_789", "Wells Fargo", BigDecimal.valueOf(30.00)));

    // When: filter by description="amazon" AND accountId="acc_123" AND bankName="chase"
    var spec =
        TransactionSpecifications.withFilter(filterByMultipleFields("amazon", "acc_123", "chase"));
    var results = transactionRepository.findAll(spec);

    // Then: only transaction matching ALL criteria is returned
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getDescription()).isEqualTo("Amazon Prime");
  }

  @Test
  void withFilter_emptyFilter_returnsAllTransactions() {
    // Given: transactions exist
    transactionRepository.save(createTransaction("Transaction 1", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Transaction 2", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Transaction 3", BigDecimal.TEN));

    // When: filter with no criteria
    var spec = TransactionSpecifications.withFilter(emptyFilter());
    var results = transactionRepository.findAll(spec);

    // Then: all transactions are returned
    assertThat(results).hasSize(3);
  }

  // ==================== Edge Cases ====================

  @Test
  void withFilter_descriptionWithBackslash_escapesCorrectly() {
    // Given: transaction with backslash in description
    transactionRepository.save(createTransaction("Payment\\Receipt", BigDecimal.TEN));
    transactionRepository.save(createTransaction("PaymentAReceipt", BigDecimal.TEN));

    // When: filter by "payment\\receipt"
    var spec = TransactionSpecifications.withFilter(filterByDescription("payment\\receipt"));
    var results = transactionRepository.findAll(spec);

    // Then: only literal backslash matches
    assertThat(results).hasSize(1);
    assertThat(results.get(0).getDescription()).isEqualTo("Payment\\Receipt");
  }

  @Test
  void withFilter_descriptionSingleCharacter_matchesCorrectly() {
    // Given: transactions
    transactionRepository.save(createTransaction("A Payment", BigDecimal.TEN));
    transactionRepository.save(createTransaction("B Payment", BigDecimal.TEN));
    transactionRepository.save(createTransaction("Payment C", BigDecimal.TEN));

    // When: filter by single character "a"
    var spec = TransactionSpecifications.withFilter(filterByDescription("a"));
    var results = transactionRepository.findAll(spec);

    // Then: matches containing "a" (case-insensitive)
    assertThat(results).hasSize(3); // All contain "a" (Payment has 'a')
    assertThat(results)
        .extracting(Transaction::getDescription)
        .allMatch(desc -> desc.toLowerCase().contains("a"));
  }

  // ==================== Helper Methods ====================

  // Filter factory methods
  private TransactionFilter filterByDescription(String description) {
    return new TransactionFilter(
        null, null, null, null, null, null, null, null, null, description, null, null, null, null);
  }

  private TransactionFilter filterByAccountId(String accountId) {
    return new TransactionFilter(
        null, accountId, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private TransactionFilter filterByBankName(String bankName) {
    return new TransactionFilter(
        null, null, bankName, null, null, null, null, null, null, null, null, null, null, null);
  }

  private TransactionFilter filterByCurrency(String currencyIsoCode) {
    return new TransactionFilter(
        null,
        null,
        null,
        null,
        null,
        currencyIsoCode,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private TransactionFilter filterByType(TransactionType type) {
    return new TransactionFilter(
        null, null, null, null, null, null, null, null, type, null, null, null, null, null);
  }

  private TransactionFilter filterByDateRange(LocalDate dateFrom, LocalDate dateTo) {
    return new TransactionFilter(
        null, null, null, dateFrom, dateTo, null, null, null, null, null, null, null, null, null);
  }

  private TransactionFilter filterByAmountRange(BigDecimal minAmount, BigDecimal maxAmount) {
    return new TransactionFilter(
        null, null, null, null, null, null, minAmount, maxAmount, null, null, null, null, null,
        null);
  }

  private TransactionFilter filterByMultipleFields(
      String description, String accountId, String bankName) {
    return new TransactionFilter(
        null,
        accountId,
        bankName,
        null,
        null,
        null,
        null,
        null,
        null,
        description,
        null,
        null,
        null,
        null);
  }

  private TransactionFilter emptyFilter() {
    return new TransactionFilter(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  // Transaction factory methods
  private Transaction createTransaction(String description, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setAccountId("test-account");
    transaction.setBankName("Test Bank");
    transaction.setDate(LocalDate.now());
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(amount);
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription(description);
    return transaction;
  }

  private Transaction createTransactionWithAccount(
      String description, String accountId, BigDecimal amount) {
    var transaction = createTransaction(description, amount);
    transaction.setAccountId(accountId);
    return transaction;
  }

  private Transaction createTransactionWithBank(String description, String bankName) {
    var transaction = createTransaction(description, BigDecimal.TEN);
    transaction.setBankName(bankName);
    return transaction;
  }

  private Transaction createTransactionWithCurrency(String description, String currencyCode) {
    var transaction = createTransaction(description, BigDecimal.TEN);
    transaction.setCurrencyIsoCode(currencyCode);
    return transaction;
  }

  private Transaction createTransactionWithType(String description, TransactionType type) {
    var transaction = createTransaction(description, BigDecimal.TEN);
    transaction.setType(type);
    return transaction;
  }

  private Transaction createTransactionWithDate(String description, LocalDate date) {
    var transaction = createTransaction(description, BigDecimal.TEN);
    transaction.setDate(date);
    return transaction;
  }

  private Transaction createComplexTransaction(
      String description, String accountId, String bankName, BigDecimal amount) {
    var transaction = createTransaction(description, amount);
    transaction.setAccountId(accountId);
    transaction.setBankName(bankName);
    return transaction;
  }
}
