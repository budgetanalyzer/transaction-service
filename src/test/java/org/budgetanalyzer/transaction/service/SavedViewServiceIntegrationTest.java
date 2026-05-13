package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.domain.ViewCriteria;
import org.budgetanalyzer.transaction.repository.SavedViewRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.SavedViewCommand;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class SavedViewServiceIntegrationTest {

  private static final String USER_ID = "test-user";

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private SavedViewService savedViewService;

  @Autowired private SavedViewRepository savedViewRepository;

  @Autowired private TransactionRepository transactionRepository;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void cleanDatabase() {
    savedViewRepository.deleteAllInBatch();
    transactionRepository.deleteAllInBatch();
  }

  @Test
  void getViewTransactions_debitCriteriaExcludesCreditTransactions() {
    var debitTransaction =
        transactionRepository.save(
            createTransaction(
                "Debit transaction", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    transactionRepository.save(
        createTransaction(
            "Credit transaction", LocalDate.of(2024, 12, 15), TransactionType.CREDIT));

    var criteria =
        new ViewCriteria(null, null, null, null, null, null, null, TransactionType.DEBIT, null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Debits", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(debitTransaction.getId());
  }

  @Test
  void getViewTransactions_mapsDateFromAndDateToIntoTransactionFiltering() {
    transactionRepository.save(
        createTransaction("Before range", LocalDate.of(2024, 11, 30), TransactionType.DEBIT));
    var inRangeTransaction =
        transactionRepository.save(
            createTransaction("In range", LocalDate.of(2024, 12, 15), TransactionType.DEBIT));
    transactionRepository.save(
        createTransaction("After range", LocalDate.of(2025, 1, 1), TransactionType.DEBIT));

    var criteria =
        new ViewCriteria(
            LocalDate.of(2024, 12, 1),
            LocalDate.of(2024, 12, 31),
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("December", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(inRangeTransaction.getId());
  }

  @Test
  void getViewTransactions_multipleBankNamesIncludeAllListedBanks() {
    final var capitalOneTransaction =
        transactionRepository.save(
            createTransaction(
                "Capital One transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    var bangkokBankTransaction =
        transactionRepository.save(
            createTransaction(
                "Bangkok Bank transaction",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "checking-12345",
                "Bangkok Bank",
                "THB"));
    transactionRepository.save(
        createTransaction(
            "Truist transaction",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "checking-12345",
            "Truist",
            "USD"));

    var criteria =
        new ViewCriteria(
            null, null, null, Set.of("capital", "bangkok"), null, null, null, null, null);
    var view = savedViewService.createView(USER_ID, new SavedViewCommand("Banks", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactly(capitalOneTransaction.getId(), bangkokBankTransaction.getId());
  }

  @Test
  void getViewTransactions_multipleAccountIdsIncludeAllListedAccounts() {
    var checkingTransaction =
        transactionRepository.save(
            createTransaction(
                "Checking transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    var savingsTransaction =
        transactionRepository.save(
            createTransaction(
                "Savings transaction",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "savings-67890",
                "Capital One",
                "USD"));
    transactionRepository.save(
        createTransaction(
            "Brokerage transaction",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "brokerage-11111",
            "Capital One",
            "USD"));

    var criteria =
        new ViewCriteria(
            null,
            null,
            Set.of("checking-12345", "savings-67890"),
            null,
            null,
            null,
            null,
            null,
            null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Accounts", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactly(checkingTransaction.getId(), savingsTransaction.getId());
  }

  @Test
  void getViewTransactions_multipleCurrencyIsoCodesIncludeAllListedCurrencies() {
    var dollarTransaction =
        transactionRepository.save(
            createTransaction(
                "Dollar transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    var bahtTransaction =
        transactionRepository.save(
            createTransaction(
                "Baht transaction",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "checking-12345",
                "Bangkok Bank",
                "THB"));
    transactionRepository.save(
        createTransaction(
            "Euro transaction",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "checking-12345",
            "Test Bank",
            "EUR"));

    var criteria =
        new ViewCriteria(null, null, null, null, Set.of("usd", "thb"), null, null, null, null);
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Currencies", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched())
        .containsExactly(dollarTransaction.getId(), bahtTransaction.getId());
  }

  @Test
  void getViewTransactions_searchTextMatchesDescriptionOrBankName() {
    var descriptionMatch =
        transactionRepository.save(
            createTransaction(
                "Coffee shop",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Neighborhood Bank",
                "USD"));
    var bankMatch =
        transactionRepository.save(
            createTransaction(
                "Grocery store",
                LocalDate.of(2024, 12, 16),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    transactionRepository.save(
        createTransaction(
            "Fuel stop",
            LocalDate.of(2024, 12, 17),
            TransactionType.DEBIT,
            "checking-12345",
            "Bangkok Bank",
            "THB"));

    var criteria =
        new ViewCriteria(null, null, null, null, null, null, null, null, "coffee capital");
    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Search text", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(descriptionMatch.getId(), bankMatch.getId());
  }

  @Test
  void getViewTransactions_blankAndEmptySetCriteriaEntriesAreIgnored() {
    var bankNames = new java.util.HashSet<String>();
    bankNames.add("capital");
    bankNames.add("");
    bankNames.add(" ");
    var criteria =
        new ViewCriteria(null, null, Set.of(), bankNames, Set.of(), null, null, null, null);
    var capitalOneTransaction =
        transactionRepository.save(
            createTransaction(
                "Capital One transaction",
                LocalDate.of(2024, 12, 15),
                TransactionType.DEBIT,
                "checking-12345",
                "Capital One",
                "USD"));
    transactionRepository.save(
        createTransaction(
            "Bangkok Bank transaction",
            LocalDate.of(2024, 12, 16),
            TransactionType.DEBIT,
            "checking-12345",
            "Bangkok Bank",
            "THB"));

    var view =
        savedViewService.createView(USER_ID, new SavedViewCommand("Blank values", criteria, false));

    var membership = savedViewService.getViewTransactions(view.getId(), USER_ID);

    assertThat(membership.matched()).containsExactly(capitalOneTransaction.getId());
  }

  private Transaction createTransaction(
      String description, LocalDate date, TransactionType transactionType) {
    return createTransaction(
        description, date, transactionType, "checking-12345", "Capital One", "USD");
  }

  private Transaction createTransaction(
      String description,
      LocalDate date,
      TransactionType transactionType,
      String accountId,
      String bankName,
      String currencyIsoCode) {
    var transaction = new Transaction();
    transaction.setAccountId(accountId);
    transaction.setBankName(bankName);
    transaction.setDate(date);
    transaction.setCurrencyIsoCode(currencyIsoCode);
    transaction.setAmount(BigDecimal.TEN);
    transaction.setType(transactionType);
    transaction.setDescription(description);
    transaction.setOwnerId(USER_ID);
    return transaction;
  }
}
