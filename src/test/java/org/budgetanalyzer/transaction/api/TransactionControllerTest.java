package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.api.response.PreviewTransaction;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.TransactionImportService;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(TransactionController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class TransactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TransactionService transactionService;

  @MockitoBean private TransactionImportService transactionImportService;

  // ==================== GET /v1/transactions/{id} ====================

  @Test
  void getTransaction_existingId_returns200AndTransaction() throws Exception {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "Coffee Shop", BigDecimal.valueOf(4.50));
    when(transactionService.getTransaction(eq(1L), anyString(), anyBoolean()))
        .thenReturn(transaction);

    // When/Then: GET returns 200 with transaction
    mockMvc
        .perform(
            get("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.description").value("Coffee Shop"))
        .andExpect(jsonPath("$.amount").value(4.50))
        .andExpect(jsonPath("$.type").value("DEBIT"))
        .andExpect(jsonPath("$.bankName").value("Test Bank"))
        .andExpect(jsonPath("$.currencyIsoCode").value("USD"));

    verify(transactionService, times(1)).getTransaction(eq(1L), anyString(), anyBoolean());
  }

  @Test
  void getTransaction_nonExistentId_returns404() throws Exception {
    // Given: transaction does not exist
    when(transactionService.getTransaction(eq(9999L), anyString(), anyBoolean()))
        .thenThrow(new ResourceNotFoundException("Transaction not found with id: 9999"));

    // When/Then: GET returns 404
    mockMvc
        .perform(
            get("/v1/transactions/9999")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Transaction not found with id: 9999"));

    verify(transactionService, times(1)).getTransaction(eq(9999L), anyString(), anyBoolean());
  }

  // ==================== GET /v1/transactions ====================

  @Test
  void getTransactions_returnsListOfTransactions() throws Exception {
    // Given: multiple transactions exist
    var transactions =
        List.of(
            createTransaction(1L, "Grocery", BigDecimal.valueOf(50.00)),
            createTransaction(2L, "Gas", BigDecimal.valueOf(35.00)),
            createTransaction(3L, "Restaurant", BigDecimal.valueOf(75.00)));

    when(transactionService.search(any(), anyString(), anyBoolean())).thenReturn(transactions);

    // When/Then: GET returns 200 with list
    mockMvc
        .perform(
            get("/v1/transactions")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].description").value("Grocery"))
        .andExpect(jsonPath("$[1].id").value(2))
        .andExpect(jsonPath("$[1].description").value("Gas"))
        .andExpect(jsonPath("$[2].id").value(3))
        .andExpect(jsonPath("$[2].description").value("Restaurant"));

    verify(transactionService, times(1)).search(any(), anyString(), anyBoolean());
  }

  // ==================== PATCH /v1/transactions/{id} ====================

  @Test
  void updateTransaction_validRequest_returns200AndUpdatedTransaction() throws Exception {
    // Given: transaction exists and update is valid
    var updatedTransaction =
        createTransaction(1L, "Updated Description", BigDecimal.valueOf(100.00));
    updatedTransaction.setAccountId("new-account-123");

    when(transactionService.updateTransaction(
            eq(1L), anyString(), anyBoolean(), eq("Updated Description"), eq("new-account-123")))
        .thenReturn(updatedTransaction);

    var requestBody =
        """
        {
          "description": "Updated Description",
          "accountId": "new-account-123"
        }
        """;

    // When/Then: PATCH returns 200 with updated transaction
    mockMvc
        .perform(
            patch("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.description").value("Updated Description"))
        .andExpect(jsonPath("$.accountId").value("new-account-123"));

    verify(transactionService, times(1))
        .updateTransaction(
            eq(1L), anyString(), anyBoolean(), eq("Updated Description"), eq("new-account-123"));
  }

  @Test
  void updateTransaction_nonExistentId_returns404() throws Exception {
    // Given: transaction does not exist
    when(transactionService.updateTransaction(
            eq(9999L), anyString(), anyBoolean(), anyString(), any()))
        .thenThrow(new ResourceNotFoundException("Transaction not found with id: 9999"));

    var requestBody =
        """
        {
          "description": "New Description"
        }
        """;

    // When/Then: PATCH returns 404
    mockMvc
        .perform(
            patch("/v1/transactions/9999")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Transaction not found with id: 9999"));

    verify(transactionService, times(1))
        .updateTransaction(eq(9999L), anyString(), anyBoolean(), eq("New Description"), eq(null));
  }

  // ==================== DELETE /v1/transactions/{id} ====================

  @Test
  void deleteTransaction_existingId_returns204() throws Exception {
    // When/Then: DELETE returns 204 No Content
    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user("auth0|test-user-123")
                        .withPermissions("transactions:delete")))
        .andExpect(status().isNoContent());

    verify(transactionService, times(1))
        .deleteTransaction(eq(1L), eq("auth0|test-user-123"), anyBoolean());
  }

  @Test
  void deleteTransaction_nonExistentId_returns404() throws Exception {
    // Given: transaction does not exist
    doThrow(new ResourceNotFoundException("Transaction not found with id: 9999"))
        .when(transactionService)
        .deleteTransaction(eq(9999L), anyString(), anyBoolean());

    // When/Then: DELETE returns 404
    mockMvc
        .perform(
            delete("/v1/transactions/9999")
                .with(
                    ClaimsHeaderTestBuilder.user("auth0|test-user-123")
                        .withPermissions("transactions:delete")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Transaction not found with id: 9999"));

    verify(transactionService, times(1)).deleteTransaction(eq(9999L), anyString(), anyBoolean());
  }

  // ==================== POST /v1/transactions/bulk-delete ====================

  @Test
  void bulkDeleteTransactions_allFound_returns200WithDeletedCount() throws Exception {
    // Given: all transactions exist
    var result = new TransactionService.BulkDeleteResult(3, List.of());
    when(transactionService.bulkDeleteTransactions(anyList(), anyString(), anyBoolean()))
        .thenReturn(result);

    var requestBody =
        """
        {
          "ids": [1, 2, 3]
        }
        """;

    // When/Then: POST returns 200 with deleted count
    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(
                    ClaimsHeaderTestBuilder.user("auth0|test-user-123")
                        .withPermissions("transactions:delete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(3))
        .andExpect(jsonPath("$.notFoundIds").isEmpty());

    verify(transactionService, times(1))
        .bulkDeleteTransactions(eq(List.of(1L, 2L, 3L)), eq("auth0|test-user-123"), anyBoolean());
  }

  @Test
  void bulkDeleteTransactions_someNotFound_returns200WithPartialSuccess() throws Exception {
    // Given: some transactions don't exist
    var result = new TransactionService.BulkDeleteResult(2, List.of(9999L, 8888L));
    when(transactionService.bulkDeleteTransactions(anyList(), anyString(), anyBoolean()))
        .thenReturn(result);

    var requestBody =
        """
        {
          "ids": [1, 2, 9999, 8888]
        }
        """;

    // When/Then: POST returns 200 with partial success
    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(
                    ClaimsHeaderTestBuilder.user("auth0|test-user-123")
                        .withPermissions("transactions:delete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(2))
        .andExpect(jsonPath("$.notFoundIds.length()").value(2))
        .andExpect(jsonPath("$.notFoundIds[0]").value(9999))
        .andExpect(jsonPath("$.notFoundIds[1]").value(8888));

    verify(transactionService, times(1))
        .bulkDeleteTransactions(
            eq(List.of(1L, 2L, 9999L, 8888L)), eq("auth0|test-user-123"), anyBoolean());
  }

  @Test
  void bulkDeleteTransactions_emptyIdList_returns400() throws Exception {
    // Given: empty ID list (validation error)
    var requestBody =
        """
        {
          "ids": []
        }
        """;

    // When/Then: POST returns 400
    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user")
                        .withPermissions("transactions:delete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  // ==================== POST /v1/transactions/preview ====================

  @Test
  void previewTransactions_csvFile_returns200WithPreviewResponse() throws Exception {
    // Given: a CSV file to preview
    var previewDto =
        createPreviewDto(LocalDate.of(2024, 1, 15), "Coffee Shop", BigDecimal.valueOf(4.50));
    var previewResponse =
        new org.budgetanalyzer.transaction.api.response.PreviewResponse(
            "transactions.csv", "capital-one", List.of(previewDto), List.of());
    when(transactionImportService.previewFile(
            eq("capital-one"), isNull(), any(MultipartFile.class)))
        .thenReturn(previewResponse);

    var csvFile =
        new MockMultipartFile(
            "file",
            "transactions.csv",
            "text/csv",
            "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50".getBytes());

    // When/Then: POST returns 200 with preview response
    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile)
                .param("format", "capital-one")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceFile").value("transactions.csv"))
        .andExpect(jsonPath("$.detectedFormat").value("capital-one"))
        .andExpect(jsonPath("$.transactions.length()").value(1))
        .andExpect(jsonPath("$.transactions[0].description").value("Coffee Shop"))
        .andExpect(jsonPath("$.warnings").isEmpty());

    verify(transactionImportService, times(1))
        .previewFile(eq("capital-one"), isNull(), any(MultipartFile.class));
  }

  @Test
  void previewTransactions_withAccountId_passesAccountIdToService() throws Exception {
    // Given: preview request with accountId
    var previewDto =
        createPreviewDto(LocalDate.of(2024, 1, 15), "Coffee Shop", BigDecimal.valueOf(4.50));
    var previewResponse =
        new org.budgetanalyzer.transaction.api.response.PreviewResponse(
            "transactions.csv", "capital-one", List.of(previewDto), List.of());
    when(transactionImportService.previewFile(
            eq("capital-one"), eq("checking-123"), any(MultipartFile.class)))
        .thenReturn(previewResponse);

    var csvFile =
        new MockMultipartFile(
            "file",
            "transactions.csv",
            "text/csv",
            "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50".getBytes());

    // When/Then: POST includes accountId
    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile)
                .param("format", "capital-one")
                .param("accountId", "checking-123")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk());

    verify(transactionImportService, times(1))
        .previewFile(eq("capital-one"), eq("checking-123"), any(MultipartFile.class));
  }

  @Test
  void previewTransactions_pdfFile_returns200WithExplicitFormat() throws Exception {
    // Given: a PDF file to preview with explicit format
    var previewDto =
        createPreviewDto(LocalDate.of(2024, 4, 12), "TAQUERIA DEL SOL", BigDecimal.valueOf(55.12));
    var previewResponse =
        new org.budgetanalyzer.transaction.api.response.PreviewResponse(
            "statement.pdf", "capital-one-yearly", List.of(previewDto), List.of());
    when(transactionImportService.previewFile(
            eq("capital-one-yearly"), isNull(), any(MultipartFile.class)))
        .thenReturn(previewResponse);

    var pdfFile =
        new MockMultipartFile(
            "file",
            "statement.pdf",
            "application/pdf",
            new byte[] {0x25, 0x50, 0x44, 0x46}); // PDF magic bytes

    // When/Then: POST with format returns 200
    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(pdfFile)
                .param("format", "capital-one-yearly")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceFile").value("statement.pdf"))
        .andExpect(jsonPath("$.detectedFormat").value("capital-one-yearly"))
        .andExpect(jsonPath("$.transactions.length()").value(1));

    verify(transactionImportService, times(1))
        .previewFile(eq("capital-one-yearly"), isNull(), any(MultipartFile.class));
  }

  // ==================== POST /v1/transactions/batch ====================

  @Test
  void batchImport_validTransactions_returns201WithCreatedTransactions() throws Exception {
    // Given: valid batch import request
    var createdTransactions =
        List.of(
            createTransaction(1L, "Transaction 1", BigDecimal.valueOf(10.00)),
            createTransaction(2L, "Transaction 2", BigDecimal.valueOf(20.00)));
    var result = new TransactionService.BatchImportResult(createdTransactions, 0);
    when(transactionService.batchImport(anyList(), anyString())).thenReturn(result);

    var requestBody =
        """
        {
          "transactions": [
            {
              "date": "2024-01-15",
              "description": "Transaction 1",
              "amount": 10.00,
              "type": "DEBIT",
              "bankName": "Test Bank",
              "currencyIsoCode": "USD"
            },
            {
              "date": "2024-01-16",
              "description": "Transaction 2",
              "amount": 20.00,
              "type": "DEBIT",
              "bankName": "Test Bank",
              "currencyIsoCode": "USD"
            }
          ]
        }
        """;

    // When/Then: POST returns 201 with created transactions
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.created").value(2))
        .andExpect(jsonPath("$.duplicatesSkipped").value(0))
        .andExpect(jsonPath("$.transactions.length()").value(2));

    verify(transactionService, times(1)).batchImport(anyList(), anyString());
  }

  @Test
  void batchImport_validationFailure_returns400WithFieldErrors() throws Exception {
    // Given: request with missing required fields (null amount, null date)
    var requestBody =
        """
        {
          "transactions": [
            {
              "description": "Transaction 1",
              "type": "DEBIT",
              "bankName": "Test Bank",
              "currencyIsoCode": "USD"
            }
          ]
        }
        """;

    // When/Then: POST returns 400 with validation errors (Jakarta Bean Validation)
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.fieldErrors.length()").value(2));
  }

  @Test
  void batchImport_emptyTransactionsList_returns400() throws Exception {
    // Given: empty transactions list
    var requestBody =
        """
        {
          "transactions": []
        }
        """;

    // When/Then: POST returns 400 with validation error
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  // ==================== GET /v1/transactions/count ====================

  @Test
  void countTransactions_returnsCount() throws Exception {
    // Given: service returns a count
    when(transactionService.countActive(any(), anyString(), anyBoolean())).thenReturn(42L);

    // When/Then: GET returns 200 with count
    mockMvc
        .perform(
            get("/v1/transactions/count")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(42));

    verify(transactionService, times(1)).countActive(any(), anyString(), anyBoolean());
  }

  @Test
  void countTransactions_withFilterParams_returns200() throws Exception {
    // Given: service returns a count
    when(transactionService.countActive(any(), anyString(), anyBoolean())).thenReturn(5L);

    // When/Then: GET with filter params returns 200 with count
    mockMvc
        .perform(
            get("/v1/transactions/count")
                .param("bankName", "Test Bank")
                .with(
                    ClaimsHeaderTestBuilder.user("test-user").withPermissions("transactions:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(5));

    verify(transactionService, times(1)).countActive(any(), anyString(), anyBoolean());
  }

  // ==================== Helper Methods ====================

  private Transaction createTransaction(Long id, String description, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setId(id);
    transaction.setAccountId("test-account");
    transaction.setBankName("Test Bank");
    transaction.setDate(LocalDate.now());
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(amount);
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription(description);
    transaction.setOwnerId("test-user");

    // Note: audit fields (createdAt, updatedAt) are managed by JPA auditing
    // and don't have public setters - they're set automatically by the framework

    return transaction;
  }

  private PreviewTransaction createPreviewDto(
      LocalDate date, String description, BigDecimal amount) {
    return new PreviewTransaction(
        date,
        description,
        amount,
        TransactionType.DEBIT,
        null, // category
        "Test Bank",
        "USD",
        null); // accountId
  }
}
