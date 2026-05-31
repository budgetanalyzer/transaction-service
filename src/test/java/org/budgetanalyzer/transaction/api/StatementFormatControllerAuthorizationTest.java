package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.service.CsvStatementFormatWizardService;
import org.budgetanalyzer.transaction.service.PdfStatementFormatWizardService;
import org.budgetanalyzer.transaction.service.StatementFormatService;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;

@WebMvcTest(StatementFormatController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class StatementFormatControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StatementFormatService statementFormatService;
  @MockitoBean private CsvStatementFormatWizardService csvStatementFormatWizardService;
  @MockitoBean private PdfStatementFormatWizardService pdfStatementFormatWizardService;

  @BeforeEach
  void setupServiceMocks() {
    when(statementFormatService.getVisibleFormats(anyString(), anyBoolean())).thenReturn(List.of());
    when(statementFormatService.getById(anyLong(), anyString(), anyBoolean()))
        .thenReturn(createStubFormat());
    when(statementFormatService.createFormat(
            any(StatementFormatCommand.class), anyString(), anyBoolean()))
        .thenReturn(createStubFormat());
    when(statementFormatService.updateFormat(
            anyLong(), any(StatementFormatPatch.class), anyString(), anyBoolean()))
        .thenReturn(createStubFormat());
    when(csvStatementFormatWizardService.analyze(any(byte[].class), anyString()))
        .thenReturn(
            new CsvWizardAnalysisResult(
                List.of(), List.of(), null, 0.0, java.util.Map.of(), List.of()));
    when(pdfStatementFormatWizardService.analyze(any(byte[].class), anyString()))
        .thenReturn(new PdfWizardAnalysisResult(List.of(), 0.0, List.of()));
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
            get("/v1/statement-formats/1")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read")))
        .andExpect(status().isOk());
  }

  @Test
  void getEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats/1")
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
            put("/v1/statement-formats/1")
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
            put("/v1/statement-formats/1")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"Updated Bank\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void csvWizardAnalyze_withWritePermission_returns200() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/analyze")
                .file(csvFile())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:write")))
        .andExpect(status().isOk());
  }

  @Test
  void csvWizardAnalyze_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/analyze")
                .file(csvFile())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void pdfWizardAnalyze_withWritePermission_returns200() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/analyze")
                .file(pdfFile())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:write")))
        .andExpect(status().isOk());
  }

  @Test
  void pdfWizardAnalyze_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/analyze")
                .file(pdfFile())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("statementformats:read")))
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
        "Capital One - Export", "Capital One", "USD", "usr_test123");
  }

  private String createValidFormatJson() {
    return """
        {
          "displayName": "New Bank - Export",
          "formatType": "CSV",
          "bankName": "New Bank",
          "defaultCurrencyIsoCode": "USD",
          "dateHeader": "Date",
          "dateFormat": "MM/dd/uu",
          "descriptionHeader": "Description",
          "creditHeader": "Amount",
          "debitHeader": "Amount"
        }
        """;
  }

  private MockMultipartFile csvFile() {
    return new MockMultipartFile(
        "file", "sample.csv", "text/csv", "Date,Description\n04/12/24,Coffee".getBytes());
  }

  private MockMultipartFile pdfFile() {
    return new MockMultipartFile(
        "file", "sample.pdf", "application/pdf", "%PDF-1.4 sample".getBytes());
  }
}
