package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.CsvStatementFormatWizardService;
import org.budgetanalyzer.transaction.service.StatementFormatService;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.CsvWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.CsvWizardPreviewResult;
import org.budgetanalyzer.transaction.service.dto.CsvWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;

@WebMvcTest(StatementFormatController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class StatementFormatControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StatementFormatService statementFormatService;
  @MockitoBean private CsvStatementFormatWizardService csvStatementFormatWizardService;

  @Nested
  class ListFormats {

    @Test
    void returnsAllFormats() throws Exception {
      var format1 = createCsvFormat("Bank 1");
      var format2 = createCsvFormat("Bank 2");
      when(statementFormatService.getVisibleFormats("usr_test123", false))
          .thenReturn(List.of(format1, format2));

      mockMvc
          .perform(
              get("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].displayName").value("Bank 1 - Export"))
          .andExpect(jsonPath("$[0].bankName").value("Bank 1"))
          .andExpect(jsonPath("$[1].displayName").value("Bank 2 - Export"));
    }

    @Test
    void returnsEmptyListWhenNoFormats() throws Exception {
      when(statementFormatService.getVisibleFormats("usr_test123", false)).thenReturn(List.of());

      mockMvc
          .perform(
              get("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }
  }

  @Nested
  class GetFormat {

    @Test
    void returnsFormatWhenFound() throws Exception {
      var format = createCsvFormat("Capital One");
      setAuditFields(
          format,
          Instant.parse("2026-04-08T10:30:00Z"),
          Instant.parse("2026-04-08T10:45:00Z"),
          "usr_creator",
          "usr_updater");
      when(statementFormatService.getById(1L, "usr_test123", false)).thenReturn(format);

      mockMvc
          .perform(
              get("/v1/statement-formats/1")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.displayName").value("Capital One - Export"))
          .andExpect(jsonPath("$.bankName").value("Capital One"))
          .andExpect(jsonPath("$.formatType").value("CSV"))
          .andExpect(jsonPath("$.defaultCurrencyIsoCode").value("USD"))
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.createdAt").value("2026-04-08T10:30:00Z"))
          .andExpect(jsonPath("$.updatedAt").value("2026-04-08T10:45:00Z"))
          .andExpect(jsonPath("$.createdBy").value("usr_creator"))
          .andExpect(jsonPath("$.updatedBy").value("usr_updater"));
    }

    @Test
    void returns404WhenNotFound() throws Exception {
      when(statementFormatService.getById(999L, "usr_test123", false))
          .thenThrow(new ResourceNotFoundException("Statement format not found with id: 999"));

      mockMvc
          .perform(
              get("/v1/statement-formats/999")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.type").value("NOT_FOUND"))
          .andExpect(jsonPath("$.message").value("Statement format not found with id: 999"));
    }
  }

  @Nested
  class CreateFormat {

    @Test
    void createsFormatSuccessfully() throws Exception {
      var format = createCsvFormat("New Bank");
      when(statementFormatService.createFormat(
              any(StatementFormatCommand.class), eq("usr_test123"), eq(false)))
          .thenReturn(format);

      mockMvc
          .perform(
              post("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
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
                      """))
          .andExpect(status().isCreated())
          .andExpect(header().exists("Location"))
          .andExpect(jsonPath("$.displayName").value("New Bank - Export"))
          .andExpect(jsonPath("$.bankName").value("New Bank"));

      verify(statementFormatService)
          .createFormat(any(StatementFormatCommand.class), eq("usr_test123"), eq(false));
    }

    @Test
    void returns422WhenServiceRejectsBusinessRule() throws Exception {
      when(statementFormatService.createFormat(
              any(StatementFormatCommand.class), eq("usr_test123"), eq(false)))
          .thenThrow(
              new BusinessException(
                  "Creating system statement formats requires statementformats:write:any.",
                  BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name()));

      mockMvc
          .perform(
              post("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "displayName": "Bank - Export",
                        "formatType": "CSV",
                        "bankName": "Bank",
                        "defaultCurrencyIsoCode": "USD",
                        "dateHeader": "Date",
                        "dateFormat": "MM/dd/uu",
                        "descriptionHeader": "Description",
                        "creditHeader": "Amount",
                        "debitHeader": "Amount"
                      }
                      """))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
          .andExpect(jsonPath("$.code").value("FORMAT_NOT_SUPPORTED"));
    }

    @Test
    void returns400WhenMissingRequiredFields() throws Exception {
      mockMvc
          .perform(
              post("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "bankName": "Bank"
                      }
                      """))
          .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenMissingDisplayName() throws Exception {
      mockMvc
          .perform(
              post("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "displayName": "",
                        "formatType": "CSV",
                        "bankName": "Bank",
                        "defaultCurrencyIsoCode": "USD"
                      }
                      """))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  class UpdateFormat {

    @Test
    void updatesFormatSuccessfully() throws Exception {
      var updatedFormat = createCsvFormat("Updated Bank");
      when(statementFormatService.updateFormat(
              eq(1L), any(StatementFormatPatch.class), eq("usr_test123"), eq(false)))
          .thenReturn(updatedFormat);

      mockMvc
          .perform(
              put("/v1/statement-formats/1")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "bankName": "Updated Bank"
                      }
                      """))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.displayName").value("Updated Bank - Export"))
          .andExpect(jsonPath("$.bankName").value("Updated Bank"));

      verify(statementFormatService)
          .updateFormat(eq(1L), any(StatementFormatPatch.class), eq("usr_test123"), eq(false));
    }

    @Test
    void returns404WhenFormatNotFound() throws Exception {
      when(statementFormatService.updateFormat(
              eq(999L), any(StatementFormatPatch.class), eq("usr_test123"), eq(false)))
          .thenThrow(new ResourceNotFoundException("Statement format not found with id: 999"));

      mockMvc
          .perform(
              put("/v1/statement-formats/999")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {
                        "bankName": "Updated Bank"
                      }
                      """))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.type").value("NOT_FOUND"));
    }
  }

  @Nested
  class CsvWizard {

    @Test
    void analyzeReturnsInferredMapping() throws Exception {
      when(csvStatementFormatWizardService.analyze(any(byte[].class), eq("sample.csv")))
          .thenReturn(
              new CsvWizardAnalysisResult(
                  List.of("Date", "Description", "Amount", "Type"),
                  List.of(Map.of("Date", "04/12/24", "Description", "Coffee Shop")),
                  singleMapping(),
                  0.95,
                  Map.of("dateColumn", 0.95),
                  List.of()));

      mockMvc
          .perform(
              multipart("/v1/statement-formats/csv-wizard/analyze")
                  .file(csvFile())
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.headers[0]").value("Date"))
          .andExpect(jsonPath("$.inferredMapping.dateColumn").value("Date"))
          .andExpect(jsonPath("$.confidence").value(0.95));
    }

    @Test
    void previewReturnsReadOnlyRows() throws Exception {
      when(csvStatementFormatWizardService.preview(
              any(byte[].class), eq("sample.csv"), any(CsvWizardMappingPreviewCommand.class)))
          .thenReturn(
              new CsvWizardPreviewResult(
                  List.of(
                      new PreviewTransaction(
                          LocalDate.parse("2024-04-12"),
                          "Coffee Shop",
                          new BigDecimal("4.50"),
                          TransactionType.DEBIT,
                          null,
                          "Example Bank",
                          "USD",
                          "checking-001")),
                  List.of()));

      mockMvc
          .perform(
              multipart("/v1/statement-formats/csv-wizard/preview")
                  .file(csvFile())
                  .file(jsonPart("request", previewRequestJson()))
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactions.length()").value(1))
          .andExpect(jsonPath("$.transactions[0].description").value("Coffee Shop"));
    }

    @Test
    void saveCreatesStatementFormat() throws Exception {
      var saved =
          StatementFormat.createCsvFormat("Example CSV", "Example Bank", "USD", "usr_test123");
      when(csvStatementFormatWizardService.save(
              any(byte[].class),
              eq("sample.csv"),
              any(CsvWizardSaveCommand.class),
              eq("usr_test123"),
              eq(false)))
          .thenReturn(saved);

      mockMvc
          .perform(
              multipart("/v1/statement-formats/csv-wizard/save")
                  .file(csvFile())
                  .file(jsonPart("request", saveRequestJson()))
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.displayName").value("Example CSV"))
          .andExpect(jsonPath("$.bankName").value("Example Bank"));
    }

    @Test
    void previewReturns422WithFieldErrorsWhenMappingInvalid() throws Exception {
      when(csvStatementFormatWizardService.preview(
              any(byte[].class), eq("sample.csv"), any(CsvWizardMappingPreviewCommand.class)))
          .thenThrow(
              new BusinessException(
                  "CSV wizard mapping validation failed.",
                  BudgetAnalyzerError.CSV_WIZARD_VALIDATION_FAILED.name(),
                  List.of(
                      org.budgetanalyzer.service.api.FieldError.of(
                          "mapping.typeColumn", "Column is required.", null))));

      mockMvc
          .perform(
              multipart("/v1/statement-formats/csv-wizard/preview")
                  .file(csvFile())
                  .file(jsonPart("request", previewRequestJson()))
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.code").value("CSV_WIZARD_VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value("mapping.typeColumn"));
    }
  }

  private StatementFormat createCsvFormat(String bankName) {
    return StatementFormat.createCsvFormat(bankName + " - Export", bankName, "USD", "usr_test123");
  }

  private CsvWizardColumnMapping singleMapping() {
    return new CsvWizardColumnMapping(
        "Date",
        "MM/dd/uu",
        "Description",
        CsvWizardAmountMode.SINGLE_AMOUNT_WITH_TYPE,
        "Amount",
        null,
        null,
        "Type",
        null);
  }

  private MockMultipartFile csvFile() {
    return new MockMultipartFile(
        "file",
        "sample.csv",
        "text/csv",
        """
        Date,Description,Amount,Type
        04/12/24,Coffee Shop,4.50,Debit
        """
            .getBytes());
  }

  private MockMultipartFile jsonPart(String name, String content) {
    return new MockMultipartFile(name, "", MediaType.APPLICATION_JSON_VALUE, content.getBytes());
  }

  private String previewRequestJson() {
    return """
        {
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "accountId": "checking-001",
          "mapping": {
            "dateColumn": "Date",
            "dateFormat": "MM/dd/uu",
            "descriptionColumn": "Description",
            "amountMode": "SINGLE_AMOUNT_WITH_TYPE",
            "amountColumn": "Amount",
            "typeColumn": "Type"
          }
        }
        """;
  }

  private String saveRequestJson() {
    return """
        {
          "displayName": "Example CSV",
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "mapping": {
            "dateColumn": "Date",
            "dateFormat": "MM/dd/uu",
            "descriptionColumn": "Description",
            "amountMode": "SINGLE_AMOUNT_WITH_TYPE",
            "amountColumn": "Amount",
            "typeColumn": "Type"
          }
        }
        """;
  }

  private void setAuditFields(
      StatementFormat format,
      Instant createdAt,
      Instant updatedAt,
      String createdBy,
      String updatedBy) {
    try {
      var auditableEntityClass = format.getClass().getSuperclass();

      var createdAtField = auditableEntityClass.getDeclaredField("createdAt");
      createdAtField.setAccessible(true);
      createdAtField.set(format, createdAt);

      var updatedAtField = auditableEntityClass.getDeclaredField("updatedAt");
      updatedAtField.setAccessible(true);
      updatedAtField.set(format, updatedAt);

      var createdByField = auditableEntityClass.getDeclaredField("createdBy");
      createdByField.setAccessible(true);
      createdByField.set(format, createdBy);

      var updatedByField = auditableEntityClass.getDeclaredField("updatedBy");
      updatedByField.setAccessible(true);
      updatedByField.set(format, updatedBy);
    } catch (ReflectiveOperationException reflectiveOperationException) {
      throw new IllegalStateException(
          "Failed to set audit fields on statement format", reflectiveOperationException);
    }
  }
}
