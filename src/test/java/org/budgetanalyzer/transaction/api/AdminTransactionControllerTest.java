package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(AdminTransactionController.class)
@Import(ServletApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@SuppressWarnings("removal")
class AdminTransactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private TransactionService transactionService;

  // ==================== POST /v1/admin/transactions/search ====================

  @Test
  void searchTransactions_withFilter_returns200AndMatchingTransactions() throws Exception {
    // Given: transactions match the filter
    var transactions =
        List.of(
            createTransaction(1L, "Grocery Store", BigDecimal.valueOf(50.00)),
            createTransaction(2L, "Gas Station", BigDecimal.valueOf(35.00)));

    when(transactionService.search(any())).thenReturn(transactions);

    var requestBody =
        """
        {
          "description": "Store",
          "minAmount": 10.00,
          "maxAmount": 100.00
        }
        """;

    // When/Then: POST returns 200 with matching transactions
    mockMvc
        .perform(
            post("/v1/admin/transactions/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].description").value("Grocery Store"))
        .andExpect(jsonPath("$[1].id").value(2))
        .andExpect(jsonPath("$[1].description").value("Gas Station"));

    verify(transactionService, times(1)).search(any());
  }

  @Test
  void searchTransactions_emptyFilter_returnsAllTransactions() throws Exception {
    // Given: empty filter returns all transactions
    var transactions =
        List.of(
            createTransaction(1L, "Transaction 1", BigDecimal.valueOf(100.00)),
            createTransaction(2L, "Transaction 2", BigDecimal.valueOf(200.00)),
            createTransaction(3L, "Transaction 3", BigDecimal.valueOf(300.00)));

    when(transactionService.search(any())).thenReturn(transactions);

    var requestBody = "{}";

    // When/Then: POST returns 200 with all transactions
    mockMvc
        .perform(
            post("/v1/admin/transactions/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));

    verify(transactionService, times(1)).search(any());
  }

  @Test
  void searchTransactions_noMatches_returnsEmptyList() throws Exception {
    // Given: no transactions match the filter
    when(transactionService.search(any())).thenReturn(List.of());

    var requestBody =
        """
        {
          "description": "NonExistent",
          "minAmount": 99999.00
        }
        """;

    // When/Then: POST returns 200 with empty list
    mockMvc
        .perform(
            post("/v1/admin/transactions/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    verify(transactionService, times(1)).search(any());
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
