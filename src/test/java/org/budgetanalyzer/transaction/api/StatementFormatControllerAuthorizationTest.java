package org.budgetanalyzer.transaction.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.service.StatementFormatService;

@WebMvcTest(StatementFormatController.class)
@Import({ServletApiExceptionHandler.class, MethodSecurityTestConfig.class})
class StatementFormatControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StatementFormatService statementFormatService;

  @BeforeEach
  void setupServiceMocks() {
    Mockito.when(statementFormatService.getAllFormats()).thenReturn(List.of());
    Mockito.when(statementFormatService.createFormat(Mockito.any())).thenReturn(createStubFormat());
  }

  // ==================== No authentication ====================

  @Test
  void noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/statement-formats")).andExpect(status().isUnauthorized());
  }

  // ==================== Read permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:read"})
  void readEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc.perform(get("/v1/statement-formats")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {"accounts:read"})
  void readEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc.perform(get("/v1/statement-formats")).andExpect(status().isForbidden());
  }

  // ==================== Admin-only CUD ====================

  @Test
  @WithMockUser(roles = {"ADMIN"})
  void adminEndpoint_withAdminRole_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "formatKey": "test-bank",
                      "displayName": "Test Bank Export",
                      "formatType": "CSV",
                      "bankName": "Test Bank",
                      "defaultCurrencyIsoCode": "USD",
                      "dateHeader": "Date",
                      "dateFormat": "MM/dd/uu",
                      "descriptionHeader": "Description",
                      "creditHeader": "Amount"
                    }
                    """))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(authorities = {"transactions:read", "transactions:write", "transactions:delete"})
  void adminEndpoint_withUserRole_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "formatKey": "test-bank",
                      "displayName": "Test Bank Export",
                      "formatType": "CSV",
                      "bankName": "Test Bank",
                      "defaultCurrencyIsoCode": "USD",
                      "dateHeader": "Date",
                      "dateFormat": "MM/dd/uu",
                      "descriptionHeader": "Description",
                      "creditHeader": "Amount"
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {"transactions:read", "transactions:write", "transactions:delete"})
  void adminDeleteEndpoint_withUserRole_returns403() throws Exception {
    mockMvc
        .perform(delete("/v1/statement-formats/capital-one").with(csrf()))
        .andExpect(status().isForbidden());
  }

  // ==================== Admin with full permissions (production JWT shape) ====================

  @Test
  @WithMockUser(authorities = {"transactions:read", "ROLE_ADMIN"})
  void admin_readEndpoint_returns200() throws Exception {
    mockMvc.perform(get("/v1/statement-formats")).andExpect(status().isOk());
  }

  // ==================== Helpers ====================

  private StatementFormat createStubFormat() {
    return StatementFormat.createCsvFormat(
        "test-bank",
        "Test Bank Export",
        "Test Bank",
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        null,
        null);
  }
}
