package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
import org.budgetanalyzer.transaction.api.response.PreviewResponse;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.TransactionImportService;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(TransactionController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class TransactionControllerAuthorizationTest {

  private static final String USER_ID = "usr_test123";
  private static final String OTHER_USER_ID = "usr_other789";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TransactionService transactionService;

  @MockitoBean private TransactionImportService transactionImportService;

  @BeforeEach
  void setupServiceMocks() {
    when(transactionService.getTransactions(anyString())).thenReturn(List.of());

    when(transactionService.batchImport(anyList(), anyString()))
        .thenReturn(new TransactionService.BatchImportResult(List.of(), 0));

    when(transactionService.bulkDeleteTransactions(anyList(), anyString(), anyBoolean()))
        .thenReturn(new TransactionService.BulkDeleteResult(2, List.of()));

    when(transactionService.countNotDeletedForUser(any(), anyString())).thenReturn(0L);

    when(transactionImportService.previewFile(anyString(), any(), any(MultipartFile.class)))
        .thenReturn(new PreviewResponse("test.csv", "capital-one", List.of(), List.of()));
  }

  // ==================== No authentication ====================

  @Test
  void noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/transactions")).andExpect(status().isUnauthorized());
  }

  // ==================== Read permission ====================

  @Test
  void readEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void readEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  // ==================== Write permission ====================

  @Test
  void writeEndpoint_withWritePermission_returns200() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "transactions": [
                        {
                          "date": "2024-01-15",
                          "description": "Coffee",
                          "amount": 4.50,
                          "type": "DEBIT",
                          "bankName": "Test Bank",
                          "currencyIsoCode": "USD"
                        }
                      ]
                    }
                    """))
        .andExpect(status().isOk());
  }

  // ==================== Delete permission ====================

  @Test
  void deleteEndpoint_withoutDeletePermission_returns403() throws Exception {
    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void bulkDeleteEndpoint_withDeletePermission_returns200() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:delete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\": [1, 2]}"))
        .andExpect(status().isOk());
  }

  // ==================== Preview permission ====================

  @Test
  void previewEndpoint_withReadPermission_returns200() throws Exception {
    var csvFile =
        new MockMultipartFile(
            "file",
            "test.csv",
            "text/csv",
            "Date,Description,Amount\n2024-01-15,Coffee,4.50".getBytes());

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile)
                .param("format", "capital-one")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void previewEndpoint_withoutReadPermission_returns403() throws Exception {
    var csvFile =
        new MockMultipartFile(
            "file",
            "test.csv",
            "text/csv",
            "Date,Description,Amount\n2024-01-15,Coffee,4.50".getBytes());

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile)
                .param("format", "capital-one")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  // ==================== Write without permission ====================

  @Test
  void writeEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "transactions": [
                        {
                          "date": "2024-01-15",
                          "description": "Coffee",
                          "amount": 4.50,
                          "type": "DEBIT",
                          "bankName": "Test Bank",
                          "currencyIsoCode": "USD"
                        }
                      ]
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  // ==================== Admin with full permissions ====================

  @Test
  void admin_readEndpoint_returns200() throws Exception {
    mockMvc
        .perform(get("/v1/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void admin_writeEndpoint_returns200() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(ClaimsHeaderTestBuilder.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "transactions": [
                        {
                          "date": "2024-01-15",
                          "description": "Coffee",
                          "amount": 4.50,
                          "type": "DEBIT",
                          "bankName": "Test Bank",
                          "currencyIsoCode": "USD"
                        }
                      ]
                    }
                    """))
        .andExpect(status().isOk());
  }

  @Test
  void admin_deleteEndpoint_returns204() throws Exception {
    mockMvc
        .perform(delete("/v1/transactions/1").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isNoContent());
  }

  // ==================== GET /v1/transactions/count authorization ====================

  @Test
  void countEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/count")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void countEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/count")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  // ==================== GET /v1/transactions/{id} ownership ====================

  @Test
  void getById_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void getById_withReadPermission_ownTransaction_returns200() throws Exception {
    var transaction = createTestTransaction(1L, "Coffee", new BigDecimal("4.50"));
    when(transactionService.getTransaction(eq(1L), eq(USER_ID), eq(false))).thenReturn(transaction);

    mockMvc
        .perform(
            get("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void getById_withReadPermission_otherUsersTransaction_returns404() throws Exception {
    when(transactionService.getTransaction(eq(1L), eq(OTHER_USER_ID), eq(false)))
        .thenThrow(new ResourceNotFoundException("Transaction not found with id: 1"));

    mockMvc
        .perform(
            get("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user(OTHER_USER_ID)
                        .withPermissions("transactions:read")))
        .andExpect(status().isNotFound());
  }

  @Test
  void getById_admin_anyTransaction_returns200() throws Exception {
    var transaction = createTestTransaction(1L, "Coffee", new BigDecimal("4.50"));
    when(transactionService.getTransaction(eq(1L), anyString(), eq(true))).thenReturn(transaction);

    mockMvc
        .perform(get("/v1/transactions/1").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  // ==================== PATCH /v1/transactions/{id} ownership ====================

  @Test
  void updateEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            patch("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateEndpoint_withWritePermission_ownTransaction_returns200() throws Exception {
    var transaction = createTestTransaction(1L, "Updated", new BigDecimal("4.50"));
    when(transactionService.updateTransaction(
            eq(1L), eq(USER_ID), eq(false), eq("Updated"), isNull()))
        .thenReturn(transaction);

    mockMvc
        .perform(
            patch("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void updateEndpoint_withWritePermission_otherUsersTransaction_returns404() throws Exception {
    when(transactionService.updateTransaction(
            eq(1L), eq(OTHER_USER_ID), eq(false), eq("Updated"), isNull()))
        .thenThrow(new ResourceNotFoundException("Transaction not found with id: 1"));

    mockMvc
        .perform(
            patch("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user(OTHER_USER_ID)
                        .withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isNotFound());
  }

  // ==================== DELETE /v1/transactions/{id} ownership ====================

  @Test
  void deleteEndpoint_withDeletePermission_ownTransaction_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:delete")))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteEndpoint_withDeletePermission_otherUsersTransaction_returns404() throws Exception {
    doThrow(new ResourceNotFoundException("Transaction not found with id: 1"))
        .when(transactionService)
        .deleteTransaction(eq(1L), eq(OTHER_USER_ID), eq(false));

    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user(OTHER_USER_ID)
                        .withPermissions("transactions:delete")))
        .andExpect(status().isNotFound());
  }

  // ==================== Test helpers ====================

  private Transaction createTestTransaction(Long id, String description, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setId(id);
    transaction.setDescription(description);
    transaction.setAmount(amount);
    transaction.setDate(LocalDate.of(2024, 1, 15));
    transaction.setType(TransactionType.DEBIT);
    transaction.setBankName("Test Bank");
    transaction.setCurrencyIsoCode("USD");
    transaction.setOwnerId(USER_ID);
    return transaction;
  }
}
