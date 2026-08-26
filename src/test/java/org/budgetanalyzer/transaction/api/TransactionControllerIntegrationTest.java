package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.FileImportRepository;
import org.budgetanalyzer.transaction.service.PreviewImportTokenService;

class TransactionControllerIntegrationTest extends ControllerIntegrationTestSupport {

  private static final String FIRST_CONTENT_HASH =
      "1111111111111111111111111111111111111111111111111111111111111111";
  private static final String SECOND_CONTENT_HASH =
      "2222222222222222222222222222222222222222222222222222222222222222";

  @Autowired private FileImportRepository fileImportRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PreviewImportTokenService previewImportTokenService;

  @Test
  void returnsOnlyOwnerTransactionsWithStableResponseFields() throws Exception {
    var ownTransaction = persistTransaction(USER_ID, "Coffee Shop");
    persistTransaction(OTHER_USER_ID, "Other User Purchase");

    mockMvc
        .perform(get("/v1/transactions").with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(ownTransaction.getId()))
        .andExpect(jsonPath("$[0].ownerId").value(USER_ID))
        .andExpect(jsonPath("$[0].description").value("Coffee Shop"))
        .andExpect(jsonPath("$[0].amount").value(4.50))
        .andExpect(jsonPath("$[0].type").value("DEBIT"))
        .andExpect(jsonPath("$[0].bankName").value("Test Bank"))
        .andExpect(jsonPath("$[0].currencyIsoCode").value("USD"))
        .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());
  }

  @Test
  void returnsPersistedTransactionById() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee Shop");

    mockMvc
        .perform(get("/v1/transactions/{id}", transaction.getId()).with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(transaction.getId()))
        .andExpect(jsonPath("$.ownerId").value(USER_ID))
        .andExpect(jsonPath("$.date").value("2024-01-15"));
  }

  @Test
  void returnsNotFoundContractForMissingTransaction() throws Exception {
    mockMvc
        .perform(get("/v1/transactions/9999").with(readUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));
  }

  @Test
  void updatesMutableFieldsAndPersistsResult() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee Shop");

    mockMvc
        .perform(
            patch("/v1/transactions/{id}", transaction.getId())
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "description": "Updated Description",
                      "accountId": "checking-123"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(transaction.getId()))
        .andExpect(jsonPath("$.description").value("Updated Description"))
        .andExpect(jsonPath("$.accountId").value("checking-123"));

    var updated = transactionRepository.findById(transaction.getId()).orElseThrow();
    assertThat(updated.getDescription()).isEqualTo("Updated Description");
    assertThat(updated.getAccountId()).isEqualTo("checking-123");
  }

  @Test
  void updatesAccountWhileRetainingDescriptionWhenDescriptionIsOmitted() throws Exception {
    var transaction = persistTransaction(USER_ID, "Original Description");

    mockMvc
        .perform(
            patch("/v1/transactions/{id}", transaction.getId())
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"savings-456\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(transaction.getId()))
        .andExpect(jsonPath("$.description").value("Original Description"))
        .andExpect(jsonPath("$.accountId").value("savings-456"));

    var updated = transactionRepository.findById(transaction.getId()).orElseThrow();
    assertThat(updated.getDescription()).isEqualTo("Original Description");
    assertThat(updated.getAccountId()).isEqualTo("savings-456");
  }

  @Test
  void softDeletesTransactionThroughDeleteEndpoint() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee Shop");

    mockMvc
        .perform(delete("/v1/transactions/{id}", transaction.getId()).with(deleteUser()))
        .andExpect(status().isNoContent());

    var deleted = transactionRepository.findById(transaction.getId()).orElseThrow();
    assertThat(deleted.isDeleted()).isTrue();

    mockMvc
        .perform(get("/v1/transactions/{id}", transaction.getId()).with(readUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));
  }

  @Test
  void bulkDeleteReturnsPartialResultAndSoftDeletesFoundRows() throws Exception {
    var firstTransaction = persistTransaction(USER_ID, "Coffee");
    var secondTransaction = persistTransaction(USER_ID, "Groceries");

    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(deleteUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"ids":[%d,%d,9999]}
                    """
                        .formatted(firstTransaction.getId(), secondTransaction.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(2))
        .andExpect(jsonPath("$.notFoundIds[0]").value(9999));

    assertThat(transactionRepository.findById(firstTransaction.getId()).orElseThrow().isDeleted())
        .isTrue();
    assertThat(transactionRepository.findById(secondTransaction.getId()).orElseThrow().isDeleted())
        .isTrue();
  }

  @Test
  void bulkDeleteRejectsEmptyIdsWithValidationContract() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(deleteUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("ids"));
  }

  @Test
  void bulkDeleteReportsMissingIdsWithoutChangingUnrelatedTransaction() throws Exception {
    var unrelatedTransaction = persistTransaction(USER_ID, "Unrelated Purchase");
    var missingId = unrelatedTransaction.getId() + 1000;

    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(deleteUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[%d]}".formatted(missingId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(0))
        .andExpect(jsonPath("$.notFoundIds.length()").value(1))
        .andExpect(jsonPath("$.notFoundIds[0]").value(missingId));

    var persisted = transactionRepository.findById(unrelatedTransaction.getId()).orElseThrow();
    assertThat(persisted.isDeleted()).isFalse();
  }

  @Test
  void previewReturnsOrderedFilesTokensAccountAndInBatchDuplicate() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var statementFormatId = parserRevision.getStatementFormat().getId();

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile("january.csv", "2024-01-15", "Coffee Shop", "4.50"))
                .file(csvFile("february.csv", "2024-01-15", "Coffee Shop", "4.50"))
                .param("statementFormatId", statementFormatId.toString())
                .param("accountId", "checking-123")
                .with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(2))
        .andExpect(jsonPath("$.files[0].sourceFile").value("january.csv"))
        .andExpect(jsonPath("$.files[0].previewImportToken").isNotEmpty())
        .andExpect(jsonPath("$.files[0].transactions[0].accountId").value("checking-123"))
        .andExpect(jsonPath("$.files[0].transactions[0].duplicate").value(false))
        .andExpect(jsonPath("$.files[1].sourceFile").value("february.csv"))
        .andExpect(jsonPath("$.files[1].previewImportToken").isNotEmpty())
        .andExpect(jsonPath("$.files[1].transactions[0].duplicate").value(true))
        .andExpect(jsonPath("$.files[1].transactions[0].duplicateReason").value("IN_BATCH"));
  }

  @Test
  void previewReturnsExistingTransactionDuplicateMetadata() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    persistTransaction(USER_ID, "Coffee Shop");

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile("statement.csv", "2024-01-15", "Coffee Shop", "4.50"))
                .param("statementFormatId", parserRevision.getStatementFormat().getId().toString())
                .with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files[0].transactions[0].duplicate").value(true))
        .andExpect(
            jsonPath("$.files[0].transactions[0].duplicateReason").value("EXISTING_TRANSACTION"));
  }

  @Test
  void previewReportsPreviousImportMetadataAfterExactFileReupload() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var statementFormatId = parserRevision.getStatementFormat().getId();
    var filename = "reupload.csv";
    var accountId = "checking-123";

    var firstPreviewResult =
        mockMvc
            .perform(
                multipart("/v1/transactions/preview")
                    .file(csvFile(filename, "2024-03-10", "Book Store", "8.25"))
                    .param("statementFormatId", statementFormatId.toString())
                    .param("accountId", accountId)
                    .with(readUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files[0].fileImport.alreadyImported").value(false))
            .andReturn();
    var firstPreviewJson =
        objectMapper.readTree(firstPreviewResult.getResponse().getContentAsByteArray());
    var previewImportToken = firstPreviewJson.at("/files/0/previewImportToken").asText();

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(exactReuploadBatchJson(previewImportToken, accountId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(1))
        .andExpect(jsonPath("$.files[0].sourceFile").value(filename));

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile(filename, "2024-03-10", "Book Store", "8.25"))
                .param("statementFormatId", statementFormatId.toString())
                .param("accountId", accountId)
                .with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files[0].fileImport.alreadyImported").value(true))
        .andExpect(jsonPath("$.files[0].fileImport.warningCode").value("FILE_ALREADY_IMPORTED"))
        .andExpect(
            jsonPath("$.files[0].fileImport.previousImport.originalFilename").value(filename))
        .andExpect(
            jsonPath("$.files[0].fileImport.previousImport.statementFormatId")
                .value(statementFormatId))
        .andExpect(jsonPath("$.files[0].fileImport.previousImport.accountId").value(accountId))
        .andExpect(jsonPath("$.files[0].fileImport.previousImport.transactionCount").value(1));

    assertThat(transactionRepository.findAll()).singleElement();
    assertThat(fileImportRepository.findAll()).singleElement();
  }

  @Test
  void previewReturnsCodedErrorWithoutPartialResponseWhenLaterFileFails() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var invalidFile =
        new MockMultipartFile(
            "files",
            "invalid.csv",
            "text/csv",
            "Date,Amount,Type\n2024-02-15,4.50,Debit".getBytes());

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile("valid.csv", "2024-01-15", "Coffee Shop", "4.50"))
                .file(invalidFile)
                .param("statementFormatId", parserRevision.getStatementFormat().getId().toString())
                .with(readUser()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("FORMAT_NOT_SUPPORTED"))
        .andExpect(jsonPath("$.files").doesNotExist());
  }

  @Test
  void previewRejectsLegacyMultipartPart() throws Exception {
    var legacyFile =
        new MockMultipartFile("file", "legacy.csv", "text/csv", "legacy content".getBytes());

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(legacyFile)
                .param("statementFormatId", "1")
                .with(readUser()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void batchImportRejectsInvalidNestedRequestShapes() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[null]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("files[0]"));

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"files\":[{\"previewImportToken\":\"token\",\"transactions\":[null]}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("files[0].transactions[0]"));

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"files\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("files"));
  }

  @Test
  void batchImportReturnsIndexedValidationFields() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "files": [{
                        "previewImportToken": "token",
                        "transactions": [{
                          "description": "Coffee Shop",
                          "type": "DEBIT",
                          "bankName": "Test Bank",
                          "currencyIsoCode": "USD"
                        }]
                      }]
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.length()").value(2))
        .andExpect(
            jsonPath("$.fieldErrors[*].field")
                .value(
                    hasItems("files[0].transactions[0].date", "files[0].transactions[0].amount")));
  }

  @Test
  void batchImportPreservesOrderedEmptyAndCreatedFileResults() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var emptyToken = createToken("empty.csv", FIRST_CONTENT_HASH, parserRevision, "checking-123");
    var acceptedToken =
        createToken("accepted.csv", SECOND_CONTENT_HASH, parserRevision, "checking-123");

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(groupedBatchJson(emptyToken, acceptedToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(1))
        .andExpect(jsonPath("$.duplicatesSkipped").value(0))
        .andExpect(jsonPath("$.duplicatesImported").value(0))
        .andExpect(jsonPath("$.files[0].sourceFile").value("empty.csv"))
        .andExpect(jsonPath("$.files[0].created").value(0))
        .andExpect(jsonPath("$.files[1].sourceFile").value("accepted.csv"))
        .andExpect(jsonPath("$.files[1].created").value(1))
        .andExpect(jsonPath("$.files[1].transactions[0].description").value("Accepted Purchase"));

    assertThat(transactionRepository.findAll())
        .singleElement()
        .satisfies(
            transaction -> {
              assertThat(transaction.getOwnerId()).isEqualTo(USER_ID);
              assertThat(transaction.getFileImport()).isNotNull();
            });
  }

  @Test
  void batchImportReturnsCodedErrorWhenAllFileGroupsAreEmpty() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var token = createToken("empty.csv", FIRST_CONTENT_HASH, parserRevision, null);

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"files":[{"previewImportToken":"%s","transactions":[]}]}
                    """
                        .formatted(token)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("BATCH_IMPORT_NO_TRANSACTIONS_CREATED"));

    assertThat(transactionRepository.findAll()).isEmpty();
  }

  @Test
  void batchImportRejectsMismatchedTokenSources() throws Exception {
    var firstParserRevision = persistCsvStatementFormat(USER_ID);
    var secondParserRevision = persistCsvStatementFormat(USER_ID);
    var firstToken = createToken("first.csv", FIRST_CONTENT_HASH, firstParserRevision, null);
    var secondToken = createToken("second.csv", SECOND_CONTENT_HASH, secondParserRevision, null);

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyGroupedBatchJson(firstToken, secondToken)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("BATCH_IMPORT_SOURCE_MISMATCH"));
  }

  @Test
  void batchImportRejectsRealPreviewTokensWithDifferentAccounts() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var statementFormatId = parserRevision.getStatementFormat().getId();
    var firstToken = previewToken("first.csv", statementFormatId, "checking-123");
    var secondToken = previewToken("second.csv", statementFormatId, "savings-456");

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyGroupedBatchJson(firstToken, secondToken)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("BATCH_IMPORT_SOURCE_MISMATCH"));

    assertThat(transactionRepository.findAll()).isEmpty();
    assertThat(fileImportRepository.findAll()).isEmpty();
  }

  @Test
  void batchImportRejectsInvalidPreviewTokenThroughRealVerifier() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"files":[{"previewImportToken":"bad-token","transactions":[]}]}
                    """))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("PREVIEW_IMPORT_TOKEN_INVALID"));
  }

  @Test
  void batchImportRoundTripsAllowDuplicateOverride() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    persistTransaction(USER_ID, "Coffee Shop");
    var token = createToken("duplicate.csv", FIRST_CONTENT_HASH, parserRevision, null);

    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchJson(token, "Coffee Shop", true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(1))
        .andExpect(jsonPath("$.duplicatesImported").value(1))
        .andExpect(jsonPath("$.files[0].transactions[0].description").value("Coffee Shop"));

    assertThat(transactionRepository.findAll()).hasSize(2);
  }

  @Test
  void countEndpointAppliesFiltersAndAuthenticatedOwnerScope() throws Exception {
    persistTransaction(USER_ID, "Coffee Shop");
    persistTransaction(USER_ID, "Groceries");
    persistTransaction(OTHER_USER_ID, "Coffee Shop");

    mockMvc
        .perform(
            get("/v1/transactions/count")
                .param("ownerId", OTHER_USER_ID)
                .param("description", "coffee")
                .with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(1));
  }

  @Test
  void searchReturnsFilteredSortedPagedContractAcrossOwners() throws Exception {
    persistDetailedTransaction(USER_ID, "Groceries", "20.00", LocalDate.of(2025, 1, 2));
    var matching =
        persistDetailedTransaction(OTHER_USER_ID, "Coffee Shop", "4.50", LocalDate.of(2025, 1, 1));
    persistDetailedTransaction(OTHER_USER_ID, "Coffee Beans", "12.00", LocalDate.of(2025, 1, 3));

    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("ownerId", OTHER_USER_ID)
                .param("description", "shop")
                .param("sort", "amount,asc")
                .param("page", "0")
                .param("size", "25")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(matching.getId()))
        .andExpect(jsonPath("$.content[0].ownerId").value(OTHER_USER_ID))
        .andExpect(jsonPath("$.content[0].description").value("Coffee Shop"))
        .andExpect(jsonPath("$.metadata.page").value(0))
        .andExpect(jsonPath("$.metadata.size").value(25))
        .andExpect(jsonPath("$.metadata.numberOfElements").value(1))
        .andExpect(jsonPath("$.metadata.totalElements").value(1))
        .andExpect(jsonPath("$.metadata.totalPages").value(1))
        .andExpect(jsonPath("$.metadata.first").value(true))
        .andExpect(jsonPath("$.metadata.last").value(true));
  }

  @Test
  void searchAmountOnlyMatchesStoredValuesAcrossCurrencies() throws Exception {
    persistDetailedTransaction(USER_ID, "Dollar match", "50.00", LocalDate.of(2025, 1, 1), "USD");
    persistDetailedTransaction(
        OTHER_USER_ID, "Baht match", "50.00", LocalDate.of(2025, 1, 2), "THB");
    persistDetailedTransaction(
        OTHER_USER_ID, "Euro outside", "500.00", LocalDate.of(2025, 1, 3), "EUR");

    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("minAmount", "40.00")
                .param("maxAmount", "60.00")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[*].currencyIsoCode").value(hasItems("USD", "THB")));
  }

  @Test
  void countAmountOnlyMatchesStoredValuesAcrossCurrencies() throws Exception {
    persistDetailedTransaction(USER_ID, "Dollar match", "50.00", LocalDate.of(2025, 1, 1), "USD");
    persistDetailedTransaction(
        OTHER_USER_ID, "Baht match", "50.00", LocalDate.of(2025, 1, 2), "THB");
    persistDetailedTransaction(
        OTHER_USER_ID, "Euro outside", "500.00", LocalDate.of(2025, 1, 3), "EUR");

    mockMvc
        .perform(
            get("/v1/transactions/search/count")
                .param("minAmount", "40.00")
                .param("maxAmount", "60.00")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(2));
  }

  @Test
  void searchCurrencyOnlyMatchesExactCurrency() throws Exception {
    persistDetailedTransaction(
        USER_ID, "Dollar transaction", "50.00", LocalDate.of(2025, 1, 1), "USD");
    var bahtTransaction =
        persistDetailedTransaction(
            OTHER_USER_ID, "Baht transaction", "500.00", LocalDate.of(2025, 1, 2), "THB");

    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("currencyIsoCode", "thb")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(bahtTransaction.getId()));
  }

  @Test
  void searchCurrencyAndAmountUsesConjunction() throws Exception {
    var matchingTransaction =
        persistDetailedTransaction(
            USER_ID, "Matching baht", "50.00", LocalDate.of(2025, 1, 1), "THB");
    persistDetailedTransaction(
        OTHER_USER_ID, "Outside baht", "500.00", LocalDate.of(2025, 1, 2), "THB");
    persistDetailedTransaction(
        OTHER_USER_ID, "Matching dollars", "50.00", LocalDate.of(2025, 1, 3), "USD");

    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("currencyIsoCode", "THB")
                .param("minAmount", "40.00")
                .param("maxAmount", "60.00")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(matchingTransaction.getId()));
  }

  @Test
  void searchAmountSortUsesStoredNumericOrderingAcrossCurrencies() throws Exception {
    var dollarTransaction =
        persistDetailedTransaction(
            USER_ID, "One hundred dollars", "100.00", LocalDate.of(2025, 1, 1), "USD");
    var bahtTransaction =
        persistDetailedTransaction(
            OTHER_USER_ID, "Twenty baht", "20.00", LocalDate.of(2025, 1, 2), "THB");
    var euroTransaction =
        persistDetailedTransaction(
            OTHER_USER_ID, "Five euros", "5.00", LocalDate.of(2025, 1, 3), "EUR");

    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("sort", "amount,asc")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.content[0].id").value(euroTransaction.getId()))
        .andExpect(jsonPath("$.content[1].id").value(bahtTransaction.getId()))
        .andExpect(jsonPath("$.content[2].id").value(dollarTransaction.getId()));
  }

  @Test
  void searchRejectsUnsupportedSortFieldWithStableErrorContract() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("sort", "deletedAt,asc")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void searchCapsPageSizeAtConfiguredMaximum() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search")
                .param("size", "500")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metadata.size").value(100));
  }

  @Test
  void crossUserCountBindsFilterCriteria() throws Exception {
    persistDetailedTransaction(USER_ID, "Coffee Shop", "4.50", LocalDate.of(2025, 1, 1));
    persistDetailedTransaction(OTHER_USER_ID, "Coffee Shop", "4.50", LocalDate.of(2025, 1, 1));
    persistDetailedTransaction(OTHER_USER_ID, "Groceries", "20.00", LocalDate.of(2025, 2, 1));

    mockMvc
        .perform(
            get("/v1/transactions/search/count")
                .param("bankName", "Test Bank")
                .param("type", "DEBIT")
                .param("dateFrom", "2025-01-01")
                .param("dateTo", "2025-01-31")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(2));
  }

  private RequestPostProcessor readUser() {
    return ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read");
  }

  private RequestPostProcessor writeUser() {
    return ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:write");
  }

  private RequestPostProcessor deleteUser() {
    return ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:delete");
  }

  private MockMultipartFile csvFile(
      String filename, String date, String description, String amount) {
    return new MockMultipartFile(
        "files",
        filename,
        "text/csv",
        """
        Date,Description,Amount,Type
        %s,%s,%s,Debit
        """
            .formatted(date, description, amount)
            .getBytes());
  }

  private String previewToken(String filename, Long statementFormatId, String accountId)
      throws Exception {
    var previewResult =
        mockMvc
            .perform(
                multipart("/v1/transactions/preview")
                    .file(csvFile(filename, "2024-02-20", "Preview Purchase", "7.75"))
                    .param("statementFormatId", statementFormatId.toString())
                    .param("accountId", accountId)
                    .with(readUser()))
            .andExpect(status().isOk())
            .andReturn();
    var previewJson = objectMapper.readTree(previewResult.getResponse().getContentAsByteArray());
    return previewJson.at("/files/0/previewImportToken").asText();
  }

  private String createToken(
      String filename, String contentHash, ParserRevision parserRevision, String accountId) {
    return previewImportTokenService.createToken(
        USER_ID,
        contentHash,
        filename,
        parserRevision.getStatementFormat().getId(),
        parserRevision.getId(),
        accountId,
        128L);
  }

  private String groupedBatchJson(String emptyToken, String acceptedToken) {
    return """
        {
          "files": [
            {"previewImportToken": "%s", "transactions": []},
            {
              "previewImportToken": "%s",
              "transactions": [{
                "date": "2025-04-11",
                "description": "Accepted Purchase",
                "amount": 9.25,
                "type": "DEBIT",
                "bankName": "Test Bank",
                "currencyIsoCode": "USD",
                "accountId": "checking-123"
              }]
            }
          ]
        }
        """
        .formatted(emptyToken, acceptedToken);
  }

  private String emptyGroupedBatchJson(String firstToken, String secondToken) {
    return """
        {
          "files": [
            {"previewImportToken": "%s", "transactions": []},
            {"previewImportToken": "%s", "transactions": []}
          ]
        }
        """
        .formatted(firstToken, secondToken);
  }

  private String batchJson(String token, String description, boolean allowDuplicate) {
    return """
        {
          "files": [{
            "previewImportToken": "%s",
            "transactions": [{
              "date": "2024-01-15",
              "description": "%s",
              "amount": 4.50,
              "type": "DEBIT",
              "bankName": "Test Bank",
              "currencyIsoCode": "USD",
              "allowDuplicate": %s
            }]
          }]
        }
        """
        .formatted(token, description, allowDuplicate);
  }

  private String exactReuploadBatchJson(String token, String accountId) {
    return """
        {
          "files": [{
            "previewImportToken": "%s",
            "transactions": [{
              "date": "2024-03-10",
              "description": "Book Store",
              "amount": 8.25,
              "type": "DEBIT",
              "bankName": "Test Bank",
              "currencyIsoCode": "USD",
              "accountId": "%s"
            }]
          }]
        }
        """
        .formatted(token, accountId);
  }

  private Transaction persistDetailedTransaction(
      String ownerId, String description, String amount, LocalDate date) {
    return persistDetailedTransaction(ownerId, description, amount, date, "USD");
  }

  private Transaction persistDetailedTransaction(
      String ownerId, String description, String amount, LocalDate date, String currencyIsoCode) {
    var transaction = new Transaction();
    transaction.setOwnerId(ownerId);
    transaction.setAccountId("checking-123");
    transaction.setDescription(description);
    transaction.setAmount(new BigDecimal(amount));
    transaction.setDate(date);
    transaction.setType(TransactionType.DEBIT);
    transaction.setBankName("Test Bank");
    transaction.setCurrencyIsoCode(currencyIsoCode);
    return transactionRepository.save(transaction);
  }
}
