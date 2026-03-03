package org.budgetanalyzer.transaction.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.security.SecurityContextUtil;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.api.response.PreviewResponse;
import org.budgetanalyzer.transaction.service.TransactionImportService;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(TransactionController.class)
@Import({
  ServletApiExceptionHandler.class,
  SecurityExceptionHandler.class,
  MethodSecurityTestConfig.class
})
class TransactionControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TransactionService transactionService;

  @MockitoBean private TransactionImportService transactionImportService;

  @BeforeEach
  void setupServiceMocks() {
    Mockito.when(
            transactionService.search(Mockito.any(), Mockito.anyString(), Mockito.anyBoolean()))
        .thenReturn(List.of());

    Mockito.when(transactionService.batchImport(Mockito.anyList(), Mockito.anyString()))
        .thenReturn(new TransactionService.BatchImportResult(List.of(), 0));

    Mockito.when(
            transactionService.bulkDeleteTransactions(
                Mockito.anyList(), Mockito.anyString(), Mockito.anyBoolean()))
        .thenReturn(new TransactionService.BulkDeleteResult(2, List.of()));

    Mockito.when(
            transactionImportService.previewFile(
                Mockito.anyString(), Mockito.any(), Mockito.any(MultipartFile.class)))
        .thenReturn(new PreviewResponse("test.csv", "capital-one", List.of(), List.of()));
  }

  // ==================== No authentication ====================

  @Test
  void noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/transactions")).andExpect(status().isUnauthorized());
  }

  // ==================== Read permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:read"})
  void readEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions")
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {"accounts:read"})
  void readEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc.perform(get("/v1/transactions")).andExpect(status().isForbidden());
  }

  // ==================== Write permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:write"})
  void writeEndpoint_withWritePermission_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123")
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
        .andExpect(status().isCreated());
  }

  // ==================== Delete permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:read"})
  void deleteEndpoint_withoutDeletePermission_returns403() throws Exception {
    mockMvc.perform(delete("/v1/transactions/1").with(csrf())).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {"transactions:delete"})
  void bulkDeleteEndpoint_withDeletePermission_returns200() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\": [1, 2]}"))
        .andExpect(status().isOk());
  }

  // ==================== Preview permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:read"})
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
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {"accounts:read"})
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
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  // ==================== Write without permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:read"})
  void writeEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(csrf())
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

  // ==================== Admin bypasses @PreAuthorize ====================

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void adminOnly_readEndpoint_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions")
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void adminOnly_writeEndpoint_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456")
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
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(authorities = {"transactions:delete", "ROLE_ADMIN"})
  void adminUser_deleteEndpoint_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456"))
        .andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void adminOnly_deleteEndpoint_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456"))
        .andExpect(status().isNoContent());
  }
}
