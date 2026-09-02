package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.FileImportRepository;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.BatchFileImportSource;
import org.budgetanalyzer.transaction.service.dto.BatchImportFile;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;

@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestClaimsSecurityConfig.class)
class TransactionServiceIntegrationTest {

  private static final String USER_ID = "test-user";
  private static final String ACCOUNT_ID = "account-123";
  private static final String FIRST_CONTENT_HASH =
      "1111111111111111111111111111111111111111111111111111111111111111";
  private static final String SECOND_CONTENT_HASH =
      "2222222222222222222222222222222222222222222222222222222222222222";
  private static final String THIRD_CONTENT_HASH =
      "3333333333333333333333333333333333333333333333333333333333333333";
  private static final String EXISTING_CONTENT_HASH =
      "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

  @Container
  private static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("testdb")
          .withUsername("test")
          .withPassword("test");

  @Autowired private TransactionService transactionService;

  @Autowired private TransactionRepository transactionRepository;

  @Autowired private FileImportRepository fileImportRepository;

  @Autowired private StatementFormatRepository statementFormatRepository;

  @Autowired private ParserRevisionRepository parserRevisionRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<Long> createdParserRevisionIds = new ArrayList<>();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @BeforeEach
  void cleanDatabase() {
    transactionRepository.deleteAllInBatch();
    fileImportRepository.deleteAllInBatch();
  }

  @AfterEach
  void cleanCreatedParserRevisions() {
    transactionRepository.deleteAllInBatch();
    fileImportRepository.deleteAllInBatch();
    parserRevisionRepository.deleteAllByIdInBatch(createdParserRevisionIds);
    createdParserRevisionIds.clear();
  }

  @Test
  void batchImportCreatesSeparateProvenanceAndDurableLinksForDistinctSources() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var januarySource =
        fileImportSource(
            FIRST_CONTENT_HASH, "january.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var februarySource =
        fileImportSource(
            SECOND_CONTENT_HASH, "february.csv", statementFormat, parserRevision, ACCOUNT_ID);

    var result =
        transactionService.batchImport(
            List.of(
                new BatchImportFile(
                    januarySource,
                    List.of(
                        previewTransaction(LocalDate.of(2025, 1, 10), "JANUARY COFFEE", "4.50"))),
                new BatchImportFile(
                    februarySource,
                    List.of(
                        previewTransaction(
                            LocalDate.of(2025, 2, 10), "FEBRUARY GROCERIES", "42.30")))),
            USER_ID);

    var fileImports = fileImportRepository.findAll();
    var januaryFileImport = fileImportByHash(fileImports, FIRST_CONTENT_HASH);
    var februaryFileImport = fileImportByHash(fileImports, SECOND_CONTENT_HASH);
    var durableLinks = durableTransactionSourceLinks();

    assertThat(result.files())
        .extracting(fileResult -> fileResult.sourceFile())
        .containsExactly("january.csv", "february.csv");
    assertThat(fileImports).hasSize(2);
    assertThat(januaryFileImport.getId()).isNotEqualTo(februaryFileImport.getId());
    assertThat(durableLinks)
        .containsExactly(
            new TransactionSourceLink("JANUARY COFFEE", januaryFileImport.getId()),
            new TransactionSourceLink("FEBRUARY GROCERIES", februaryFileImport.getId()));
  }

  @Test
  void batchImportReusesExistingOwnerScopedProvenance() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var existingFileImport =
        fileImportRepository.save(
            FileImport.create(
                EXISTING_CONTENT_HASH,
                "original-name.csv",
                statementFormat.getId(),
                parserRevision.getId(),
                ACCOUNT_ID,
                512L,
                1,
                USER_ID));
    var source =
        fileImportSource(
            EXISTING_CONTENT_HASH,
            "renamed-source.csv",
            statementFormat,
            parserRevision,
            ACCOUNT_ID);

    transactionService.batchImport(
        List.of(
            new BatchImportFile(
                source,
                List.of(previewTransaction(LocalDate.of(2025, 3, 10), "REUSED SOURCE", "12.34")))),
        USER_ID);

    assertThat(fileImportRepository.findAll())
        .extracting(FileImport::getId)
        .containsExactly(existingFileImport.getId());
    assertThat(durableTransactionSourceLinks())
        .containsExactly(new TransactionSourceLink("REUSED SOURCE", existingFileImport.getId()));
  }

  @Test
  void batchImportPreservesEmptyGroupWithoutProvenanceWhenAnotherGroupSucceeds() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var emptySource =
        fileImportSource(
            FIRST_CONTENT_HASH, "empty.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var acceptedSource =
        fileImportSource(
            SECOND_CONTENT_HASH, "accepted.csv", statementFormat, parserRevision, ACCOUNT_ID);

    var result =
        transactionService.batchImport(
            List.of(
                new BatchImportFile(emptySource, List.of()),
                new BatchImportFile(
                    acceptedSource,
                    List.of(
                        previewTransaction(
                            LocalDate.of(2025, 4, 11), "ACCEPTED TRANSACTION", "9.25")))),
            USER_ID);

    assertThat(result.files())
        .extracting(
            fileResult -> fileResult.sourceFile(),
            fileResult -> fileResult.createdTransactions().size(),
            fileResult -> fileResult.duplicatesSkipped(),
            fileResult -> fileResult.duplicatesImported())
        .containsExactly(tuple("empty.csv", 0, 0, 0), tuple("accepted.csv", 1, 0, 0));
    assertThat(result.created()).isEqualTo(1);
    assertThat(result.duplicatesSkipped()).isZero();
    assertThat(result.duplicatesImported()).isZero();
    assertThat(fileImportRepository.findAll())
        .extracting(FileImport::getContentHash)
        .containsExactly(SECOND_CONTENT_HASH);
  }

  @Test
  void batchImportRejectsAllEmptyRequestWithoutCreatingProvenance() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var source =
        fileImportSource(
            FIRST_CONTENT_HASH, "all-empty.csv", statementFormat, parserRevision, ACCOUNT_ID);

    assertThatThrownBy(
            () ->
                transactionService.batchImport(
                    List.of(new BatchImportFile(source, List.of())), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.BATCH_IMPORT_NO_TRANSACTIONS_CREATED.name());
            });
    assertThat(transactionRepository.findAll()).isEmpty();
    assertThat(fileImportRepository.findAll()).isEmpty();
  }

  @Test
  void batchImportKeepsSameFileRepeatsAndSkipsDeterministicLaterFileDuplicate() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var repeated = previewTransaction(LocalDate.of(2025, 6, 10), "REPEATED ROW", "6.25");
    var firstSource =
        fileImportSource(
            FIRST_CONTENT_HASH, "first.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var laterSource =
        fileImportSource(
            SECOND_CONTENT_HASH, "later.csv", statementFormat, parserRevision, ACCOUNT_ID);

    var result =
        transactionService.batchImport(
            List.of(
                new BatchImportFile(firstSource, List.of(repeated, repeated)),
                new BatchImportFile(laterSource, List.of(repeated))),
            USER_ID);

    assertThat(result.created()).isEqualTo(2);
    assertThat(result.duplicatesSkipped()).isEqualTo(1);
    assertThat(result.files().get(0).createdTransactions()).hasSize(2);
    assertThat(result.files().get(0).duplicatesSkipped()).isZero();
    assertThat(result.files().get(1).createdTransactions()).isEmpty();
    assertThat(result.files().get(1).duplicatesSkipped()).isEqualTo(1);
    assertThat(transactionRepository.findAll()).hasSize(2);
    assertThat(fileImportRepository.findAll())
        .extracting(FileImport::getContentHash)
        .containsExactly(FIRST_CONTENT_HASH);
  }

  @Test
  void batchImportAcceptsDifferentParserRevisionsUnderOneStatementFormat() {
    var statementFormat = enabledCsvStatementFormat();
    var firstParserRevision = parserRevision(statementFormat);
    var secondParserRevision = createNextParserRevision(statementFormat, firstParserRevision);
    var firstSource =
        fileImportSource(
            FIRST_CONTENT_HASH,
            "first-revision.csv",
            statementFormat,
            firstParserRevision,
            ACCOUNT_ID);
    var secondSource =
        fileImportSource(
            SECOND_CONTENT_HASH,
            "second-revision.csv",
            statementFormat,
            secondParserRevision,
            ACCOUNT_ID);

    var result =
        transactionService.batchImport(
            List.of(
                new BatchImportFile(
                    firstSource,
                    List.of(
                        previewTransaction(LocalDate.of(2025, 7, 10), "FIRST REVISION", "10.00"))),
                new BatchImportFile(
                    secondSource,
                    List.of(
                        previewTransaction(
                            LocalDate.of(2025, 7, 11), "SECOND REVISION", "11.00")))),
            USER_ID);

    assertThat(result.files())
        .extracting(fileResult -> fileResult.sourceFile())
        .containsExactly("first-revision.csv", "second-revision.csv");
    assertThat(fileImportRepository.findAll())
        .extracting(FileImport::getParserRevisionId)
        .containsExactlyInAnyOrder(firstParserRevision.getId(), secondParserRevision.getId());
  }

  @Test
  void batchImportRollsBackEarlierWritesWhenLaterGroupViolatesDatabaseConstraint() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var existingFileImport =
        fileImportRepository.save(
            FileImport.create(
                EXISTING_CONTENT_HASH,
                "existing.csv",
                statementFormat.getId(),
                parserRevision.getId(),
                ACCOUNT_ID,
                256L,
                1,
                USER_ID));
    var existingTransaction =
        transactionRepository.save(
            transaction(
                previewTransaction(LocalDate.of(2025, 8, 1), "PREEXISTING TRANSACTION", "3.00")));
    var firstSource =
        fileImportSource(
            FIRST_CONTENT_HASH, "valid-first.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var failingSource =
        fileImportSource(
            THIRD_CONTENT_HASH, "invalid-later.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var invalidCurrencyTransaction =
        previewTransaction(LocalDate.of(2025, 8, 3), "INVALID CURRENCY WIDTH", "5.00", "USDX");

    assertThatThrownBy(
            () ->
                transactionService.batchImport(
                    List.of(
                        new BatchImportFile(
                            firstSource,
                            List.of(
                                previewTransaction(
                                    LocalDate.of(2025, 8, 2), "VALID FIRST WRITE", "4.00"))),
                        new BatchImportFile(failingSource, List.of(invalidCurrencyTransaction))),
                    USER_ID))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(transactionRepository.findAll())
        .extracting(Transaction::getId)
        .containsExactly(existingTransaction.getId());
    assertThat(fileImportRepository.findAll())
        .extracting(FileImport::getId)
        .containsExactly(existingFileImport.getId());
    assertThat(durableTransactionSourceLinks())
        .containsExactly(new TransactionSourceLink("PREEXISTING TRANSACTION", null));
  }

  @Test
  void batchImportSameTransactionsSubmittedTwiceRejectsSecondSubmission() {
    var transactions =
        List.of(
            previewTransaction(LocalDate.of(2025, 11, 18), "COFFEE SHOP", "9.97"),
            previewTransaction(LocalDate.of(2025, 11, 19), "GROCERY STORE", "42.30"));

    var fileImportSource = fileImportSource("statement-duplicates.csv");
    var firstResult =
        transactionService.batchImport(
            List.of(new BatchImportFile(fileImportSource, transactions)), USER_ID);

    assertThat(firstResult.createdTransactions()).hasSize(2);
    assertThat(firstResult.duplicatesSkipped()).isZero();
    assertThat(firstResult.duplicatesImported()).isZero();
    assertThatThrownBy(
            () ->
                transactionService.batchImport(
                    List.of(new BatchImportFile(fileImportSource, transactions)), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.BATCH_IMPORT_NO_TRANSACTIONS_CREATED.name());
            });
    assertThat(transactionRepository.findAll()).hasSize(2);
  }

  @Test
  void batchImportDuplicateSubmittedWithOverrideImportsDuplicate() {
    var transaction = previewTransaction(LocalDate.of(2025, 11, 18), "COFFEE SHOP", "9.97");
    var duplicate =
        new PreviewTransaction(
            transaction.date(),
            transaction.description(),
            transaction.amount(),
            transaction.type(),
            transaction.category(),
            transaction.bankName(),
            transaction.currencyIsoCode(),
            transaction.accountId(),
            true);

    var fileImportSource = fileImportSource("statement-override.csv");
    transactionService.batchImport(
        List.of(new BatchImportFile(fileImportSource, List.of(transaction))), USER_ID);
    var result =
        transactionService.batchImport(
            List.of(new BatchImportFile(fileImportSource, List.of(duplicate))), USER_ID);

    assertThat(result.createdTransactions()).hasSize(1);
    assertThat(result.duplicatesSkipped()).isZero();
    assertThat(result.duplicatesImported()).isEqualTo(1);
    assertThat(transactionRepository.findAll()).hasSize(2);
  }

  @Test
  void batchImportTracksNormalizedDuplicateAcrossPersistedAndLaterFileRows() {
    var date = LocalDate.of(2025, 11, 20);
    var persistedTransaction =
        transaction(previewTransaction(date, "STORE PAYMENT A-A-A", "100.00"));
    transactionRepository.save(persistedTransaction);
    var allowedDuplicate = previewTransaction(date, "store payment aaa", "100.00", true);
    var laterDuplicate = previewTransaction(date, "STORE  PAYMENT AAA", "100.00");
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var firstSource =
        fileImportSource(
            FIRST_CONTENT_HASH, "first.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var laterSource =
        fileImportSource(
            SECOND_CONTENT_HASH, "later.csv", statementFormat, parserRevision, ACCOUNT_ID);

    var result =
        transactionService.batchImport(
            List.of(
                new BatchImportFile(firstSource, List.of(allowedDuplicate)),
                new BatchImportFile(laterSource, List.of(laterDuplicate))),
            USER_ID);

    assertThat(result.created()).isEqualTo(1);
    assertThat(result.duplicatesSkipped()).isEqualTo(1);
    assertThat(result.duplicatesImported()).isEqualTo(1);
    assertThat(result.files())
        .extracting(
            fileResult -> fileResult.sourceFile(),
            fileResult -> fileResult.createdTransactions().size(),
            fileResult -> fileResult.duplicatesSkipped(),
            fileResult -> fileResult.duplicatesImported())
        .containsExactly(tuple("first.csv", 1, 0, 1), tuple("later.csv", 0, 1, 0));
    assertThat(transactionRepository.findAll())
        .extracting(Transaction::getDescription)
        .containsExactlyInAnyOrder("STORE PAYMENT A-A-A", "store payment aaa");
    assertThat(fileImportRepository.findAll())
        .extracting(FileImport::getContentHash)
        .containsExactly(FIRST_CONTENT_HASH);
  }

  @Test
  void batchImportAggregatesBusinessDateErrorsBeforePersistence() {
    var statementFormat = enabledStatementFormat();
    var parserRevision = parserRevision(statementFormat);
    var source =
        fileImportSource(
            FIRST_CONTENT_HASH, "invalid-dates.csv", statementFormat, parserRevision, ACCOUNT_ID);
    var tooOld = previewTransaction(LocalDate.of(1999, 12, 31), "TOO OLD", "10.00");
    var tooFarInFuture =
        previewTransaction(LocalDate.now().plusDays(2), "TOO FAR IN FUTURE", "20.00");

    assertThatThrownBy(
            () ->
                transactionService.batchImport(
                    List.of(new BatchImportFile(source, List.of(tooOld, tooFarInFuture))), USER_ID))
        .isInstanceOf(BatchValidationException.class)
        .satisfies(
            exception -> {
              var batchValidationException = (BatchValidationException) exception;
              assertThat(batchValidationException.getFieldErrors())
                  .extracting(fieldError -> fieldError.getField())
                  .containsExactly(
                      "files[0].transactions[0].date", "files[0].transactions[1].date");
            });
    assertThat(transactionRepository.findAll()).isEmpty();
    assertThat(fileImportRepository.findAll()).isEmpty();
  }

  @Test
  void bulkDeleteTransactionsDuplicateInputIdsDeletesOnceAndReportsSecondAsNotFound() {
    var transaction = new Transaction();
    transaction.setAccountId("checking");
    transaction.setBankName("Capital One");
    transaction.setDate(LocalDate.of(2025, 11, 18));
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(new BigDecimal("9.97"));
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription("COFFEE SHOP");
    transaction.setOwnerId(USER_ID);
    var savedTransaction = transactionRepository.save(transaction);

    var result =
        transactionService.bulkDeleteTransactions(
            List.of(savedTransaction.getId(), savedTransaction.getId()), USER_ID, false);

    assertThat(result.deletedCount()).isEqualTo(1);
    assertThat(result.notFoundIds()).containsExactly(savedTransaction.getId());
    assertThat(transactionRepository.findByIdNotDeleted(savedTransaction.getId())).isEmpty();
  }

  private PreviewTransaction previewTransaction(LocalDate date, String description, String amount) {
    return previewTransaction(date, description, amount, "USD");
  }

  private PreviewTransaction previewTransaction(
      LocalDate date, String description, String amount, String currencyIsoCode) {
    return new PreviewTransaction(
        date,
        description,
        new BigDecimal(amount),
        TransactionType.DEBIT,
        null,
        "Capital One",
        currencyIsoCode,
        "capital-one-credit");
  }

  private PreviewTransaction previewTransaction(
      LocalDate date, String description, String amount, boolean allowDuplicate) {
    return new PreviewTransaction(
        date,
        description,
        new BigDecimal(amount),
        TransactionType.DEBIT,
        null,
        "Capital One",
        "USD",
        "capital-one-credit",
        allowDuplicate);
  }

  private BatchFileImportSource fileImportSource(String originalFilename) {
    var statementFormat = enabledStatementFormat();
    return fileImportSource(
        FIRST_CONTENT_HASH,
        originalFilename,
        statementFormat,
        parserRevision(statementFormat),
        ACCOUNT_ID);
  }

  private BatchFileImportSource fileImportSource(
      String contentHash,
      String originalFilename,
      StatementFormat statementFormat,
      ParserRevision parserRevision,
      String accountId) {
    return new BatchFileImportSource(
        contentHash,
        originalFilename,
        statementFormat.getId(),
        parserRevision.getId(),
        accountId,
        1024L);
  }

  private StatementFormat enabledStatementFormat() {
    return statementFormatRepository.findAll().stream()
        .filter(StatementFormat::isEnabled)
        .findFirst()
        .orElseThrow();
  }

  private StatementFormat enabledCsvStatementFormat() {
    return statementFormatRepository.findAll().stream()
        .filter(StatementFormat::isEnabled)
        .filter(statementFormat -> statementFormat.getFormatType() == FormatType.CSV)
        .findFirst()
        .orElseThrow();
  }

  private ParserRevision parserRevision(StatementFormat statementFormat) {
    return parserRevisionRepository
        .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
            statementFormat.getId())
        .getFirst();
  }

  private ParserRevision createNextParserRevision(
      StatementFormat statementFormat, ParserRevision firstParserRevision) {
    var nextRevisionNumber =
        parserRevisionRepository
                .findByStatementFormatIdAndEnabledTrueOrderByPriorityDescRevisionNumberDesc(
                    statementFormat.getId())
                .stream()
                .mapToInt(ParserRevision::getRevisionNumber)
                .max()
                .orElseThrow()
            + 1;
    var parserRevision =
        parserRevisionRepository.save(
            ParserRevision.createCsvColumnConfig(
                statementFormat, nextRevisionNumber, firstParserRevision.getParserConfig()));
    createdParserRevisionIds.add(parserRevision.getId());
    return parserRevision;
  }

  private Transaction transaction(PreviewTransaction previewTransaction) {
    var transaction = new Transaction();
    transaction.setAccountId(previewTransaction.accountId());
    transaction.setBankName(previewTransaction.bankName());
    transaction.setDate(previewTransaction.date());
    transaction.setCurrencyIsoCode(previewTransaction.currencyIsoCode());
    transaction.setAmount(previewTransaction.amount());
    transaction.setType(previewTransaction.type());
    transaction.setDescription(previewTransaction.description());
    transaction.setOwnerId(USER_ID);
    return transaction;
  }

  private FileImport fileImportByHash(List<FileImport> fileImports, String contentHash) {
    return fileImports.stream()
        .filter(fileImport -> fileImport.getContentHash().equals(contentHash))
        .findFirst()
        .orElseThrow();
  }

  private List<TransactionSourceLink> durableTransactionSourceLinks() {
    return jdbcTemplate.query(
        "SELECT description, file_import_id FROM transaction ORDER BY id",
        (resultSet, rowNumber) ->
            new TransactionSourceLink(
                resultSet.getString("description"),
                resultSet.getObject("file_import_id", Long.class)));
  }

  private record TransactionSourceLink(String description, Long fileImportId) {}
}
