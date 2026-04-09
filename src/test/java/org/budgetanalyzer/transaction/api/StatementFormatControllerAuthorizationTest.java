package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.service.StatementFormatService;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;

@WebMvcTest(StatementFormatController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class StatementFormatControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StatementFormatService statementFormatService;

  @BeforeEach
  void setupServiceMocks() {
    when(statementFormatService.getAllFormats()).thenReturn(List.of());
    when(statementFormatService.getByFormatKey(anyString())).thenReturn(createStubFormat());
    when(statementFormatService.createFormat(any(StatementFormatCommand.class)))
        .thenReturn(createStubFormat());
    when(statementFormatService.updateFormat(anyString(), any(StatementFormatPatch.class)))
        .thenReturn(createStubFormat());
  }

  // ==================== No authentication ====================

  @Test
  void noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/statement-formats")).andExpect(status().isUnauthorized());
  }

  // ==================== Read permission ====================

  @Test
  void listEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read")))
        .andExpect(status().isOk());
  }

  @Test
  void listEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void getEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats/capital-one")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read")))
        .andExpect(status().isOk());
  }

  @Test
  void getEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats/capital-one")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  // ==================== Write permission ====================

  @Test
  void createEndpoint_withWritePermission_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createValidFormatJson()))
        .andExpect(status().isCreated());
  }

  @Test
  void createEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createValidFormatJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateEndpoint_withWritePermission_returns200() throws Exception {
    mockMvc
        .perform(
            put("/v1/statement-formats/capital-one")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"Updated Bank\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void updateEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            put("/v1/statement-formats/capital-one")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"Updated Bank\"}"))
        .andExpect(status().isForbidden());
  }

  // ==================== Admin with full permissions ====================

  @Test
  void admin_readEndpoint_returns200() throws Exception {
    mockMvc
        .perform(get("/v1/statement-formats").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void admin_writeEndpoint_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(ClaimsHeaderTestBuilder.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createValidFormatJson()))
        .andExpect(status().isCreated());
  }

  // ==================== Helpers ====================

  private StatementFormat createStubFormat() {
    return StatementFormat.createCsvFormat(
        "capital-one",
        "Capital One - Export",
        "Capital One",
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        null,
        null);
  }

  private String createValidFormatJson() {
    return """
        {
          "formatKey": "new-format",
          "displayName": "New Bank - Export",
          "formatType": "CSV",
          "bankName": "New Bank",
          "defaultCurrencyIsoCode": "USD",
          "dateHeader": "Date",
          "dateFormat": "MM/dd/uu",
          "descriptionHeader": "Description",
          "creditHeader": "Amount"
        }
        """;
  }
}
