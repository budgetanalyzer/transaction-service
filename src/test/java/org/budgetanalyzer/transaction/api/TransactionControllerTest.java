package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.TransactionImportService;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(TransactionController.class)
@Import(ServletApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@SuppressWarnings("removal")
class TransactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private TransactionService transactionService;

  @MockBean private TransactionImportService transactionImportService;

  // ==================== GET /v1/transactions/{id} ====================

  @Test
  @WithMockUser
  void getTransaction_existingId_returns200AndTransaction() throws Exception {
    // Given: a transaction exists
    var transaction = createTransaction(1L, "Coffee Shop", BigDecimal.valueOf(4.50));
    when(transactionService.getTransaction(1L)).thenReturn(transaction);

    // When/Then: GET returns 200 with transaction
    mockMvc
        .perform(get("/v1/transactions/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.description").value("Coffee Shop"))
        .andExpect(jsonPath("$.amount").value(4.50))
        .andExpect(jsonPath("$.type").value("DEBIT"))
        .andExpect(jsonPath("$.bankName").value("Test Bank"))
        .andExpect(jsonPath("$.currencyIsoCode").value("USD"));

    verify(transactionService, times(1)).getTransaction(1L);
  }

  @Test
  @WithMockUser
  void getTransaction_nonExistentId_returns404() throws Exception {
    // Given: transaction does not exist
    when(transactionService.getTransaction(9999L))
        .thenThrow(new ResourceNotFoundException("Transaction not found with id: 9999"));

    // When/Then: GET returns 404
    mockMvc
        .perform(get("/v1/transactions/9999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Transaction not found with id: 9999"));

    verify(transactionService, times(1)).getTransaction(9999L);
  }

  @Test
  @Disabled("Security context testing - will be updated with user spaces implementation")
  void getTransaction_unauthenticated_returns401() throws Exception {
    // When/Then: GET without authentication returns 401
    mockMvc.perform(get("/v1/transactions/1")).andExpect(status().isUnauthorized());
  }

  // ==================== GET /v1/transactions ====================

  @Test
  @WithMockUser
  void getTransactions_returnsListOfTransactions() throws Exception {
    // Given: multiple transactions exist
    var transactions =
        List.of(
            createTransaction(1L, "Grocery", BigDecimal.valueOf(50.00)),
            createTransaction(2L, "Gas", BigDecimal.valueOf(35.00)),
            createTransaction(3L, "Restaurant", BigDecimal.valueOf(75.00)));

    when(transactionService.search(any())).thenReturn(transactions);

    // When/Then: GET returns 200 with list
    mockMvc
        .perform(get("/v1/transactions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].description").value("Grocery"))
        .andExpect(jsonPath("$[1].id").value(2))
        .andExpect(jsonPath("$[1].description").value("Gas"))
        .andExpect(jsonPath("$[2].id").value(3))
        .andExpect(jsonPath("$[2].description").value("Restaurant"));

    verify(transactionService, times(1)).search(any());
  }

  // ==================== PATCH /v1/transactions/{id} ====================

  @Test
  @WithMockUser
  void updateTransaction_validRequest_returns200AndUpdatedTransaction() throws Exception {
    // Given: transaction exists and update is valid
    var updatedTransaction =
        createTransaction(1L, "Updated Description", BigDecimal.valueOf(100.00));
    updatedTransaction.setAccountId("new-account-123");

    when(transactionService.updateTransaction(
            eq(1L), eq("Updated Description"), eq("new-account-123")))
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.description").value("Updated Description"))
        .andExpect(jsonPath("$.accountId").value("new-account-123"));

    verify(transactionService, times(1))
        .updateTransaction(1L, "Updated Description", "new-account-123");
  }

  @Test
  @WithMockUser
  void updateTransaction_nonExistentId_returns404() throws Exception {
    // Given: transaction does not exist
    when(transactionService.updateTransaction(eq(9999L), anyString(), any()))
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Transaction not found with id: 9999"));

    verify(transactionService, times(1))
        .updateTransaction(eq(9999L), eq("New Description"), eq(null));
  }

  // ==================== DELETE /v1/transactions/{id} ====================

  @Test
  @Disabled("Security context testing - will be updated with user spaces implementation")
  @WithMockUser(username = "auth0|test-user-123")
  void deleteTransaction_existingId_returns204() throws Exception {
    // When/Then: DELETE returns 204 No Content
    mockMvc.perform(delete("/v1/transactions/1")).andExpect(status().isNoContent());

    verify(transactionService, times(1)).deleteTransaction(eq(1L), eq("auth0|test-user-123"));
  }

  @Test
  @Disabled("Security context testing - will be updated with user spaces implementation")
  @WithMockUser(username = "auth0|test-user-123")
  void deleteTransaction_nonExistentId_returns404() throws Exception {
    // Given: transaction does not exist
    doThrow(new ResourceNotFoundException("Transaction not found with id: 9999"))
        .when(transactionService)
        .deleteTransaction(eq(9999L), anyString());

    // When/Then: DELETE returns 404
    mockMvc
        .perform(delete("/v1/transactions/9999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Transaction not found with id: 9999"));

    verify(transactionService, times(1)).deleteTransaction(eq(9999L), anyString());
  }

  // ==================== POST /v1/transactions/bulk-delete ====================

  @Test
  @Disabled("Security context testing - will be updated with user spaces implementation")
  @WithMockUser(username = "auth0|test-user-123")
  void bulkDeleteTransactions_allFound_returns200WithDeletedCount() throws Exception {
    // Given: all transactions exist
    var result = new TransactionService.BulkDeleteResult(3, List.of());
    when(transactionService.bulkDeleteTransactions(anyList(), anyString())).thenReturn(result);

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
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(3))
        .andExpect(jsonPath("$.notFoundIds").isEmpty());

    verify(transactionService, times(1))
        .bulkDeleteTransactions(List.of(1L, 2L, 3L), "auth0|test-user-123");
  }

  @Test
  @Disabled("Security context testing - will be updated with user spaces implementation")
  @WithMockUser(username = "auth0|test-user-123")
  void bulkDeleteTransactions_someNotFound_returns200WithPartialSuccess() throws Exception {
    // Given: some transactions don't exist
    var result = new TransactionService.BulkDeleteResult(2, List.of(9999L, 8888L));
    when(transactionService.bulkDeleteTransactions(anyList(), anyString())).thenReturn(result);

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
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(2))
        .andExpect(jsonPath("$.notFoundIds.length()").value(2))
        .andExpect(jsonPath("$.notFoundIds[0]").value(9999))
        .andExpect(jsonPath("$.notFoundIds[1]").value(8888));

    verify(transactionService, times(1))
        .bulkDeleteTransactions(List.of(1L, 2L, 9999L, 8888L), "auth0|test-user-123");
  }

  @Test
  @WithMockUser
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
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

    // Note: audit fields (createdAt, updatedAt) are managed by JPA auditing
    // and don't have public setters - they're set automatically by the framework

    return transaction;
  }
}
