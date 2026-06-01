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
import org.budgetanalyzer.transaction.service.PdfStatementFormatWizardService;
import org.budgetanalyzer.transaction.service.StatementFormatService;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.CsvWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.CsvWizardPreviewResult;
import org.budgetanalyzer.transaction.service.dto.CsvWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAnalysisResult;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;
import org.budgetanalyzer.transaction.service.dto.PdfWizardMappingPreviewCommand;
import org.budgetanalyzer.transaction.service.dto.PdfWizardPreviewResult;
import org.budgetanalyzer.transaction.service.dto.PdfWizardSaveCommand;
import org.budgetanalyzer.transaction.service.dto.PdfWizardTableCandidate;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatListItem;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;

@WebMvcTest(StatementFormatController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class StatementFormatControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StatementFormatService statementFormatService;
  @MockitoBean private CsvStatementFormatWizardService csvStatementFormatWizardService;
  @MockitoBean private PdfStatementFormatWizardService pdfStatementFormatWizardService;

  @Nested
  class ListFormats {

    @Test
    void returnsAllFormats() throws Exception {
      var format1 = createCsvFormat("Bank 1");
      var format2 = createCsvFormat("Bank 2");
      when(statementFormatService.listFormats("usr_test123", false, false))
          .thenReturn(
              List.of(
                  new StatementFormatListItem(format1, false),
                  new StatementFormatListItem(format2, false)));

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
          .andExpect(jsonPath("$[0].hidden").value(false))
          .andExpect(jsonPath("$[1].displayName").value("Bank 2 - Export"));
    }

    @Test
    void returnsEmptyListWhenNoFormats() throws Exception {
      when(statementFormatService.listFormats("usr_test123", false, false)).thenReturn(List.of());

      mockMvc
          .perform(
              get("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void passesIncludeHiddenToService() throws Exception {
      var format = createCsvFormat("Hidden Bank");
      when(statementFormatService.listFormats("usr_test123", false, true))
          .thenReturn(List.of(new StatementFormatListItem(format, true)));

      mockMvc
          .perform(
              get("/v1/statement-formats?includeHidden=true")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].displayName").value("Hidden Bank - Export"))
          .andExpect(jsonPath("$[0].hidden").value(true));

      verify(statementFormatService).listFormats("usr_test123", false, true);
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
  class UserPreference {

    @Test
    void hideFormatReturnsNoContent() throws Exception {
      mockMvc
          .perform(
              post("/v1/statement-formats/1/hide")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isNoContent());

      verify(statementFormatService).hideFormat(1L, "usr_test123");
    }

    @Test
    void unhideFormatReturnsNoContent() throws Exception {
      mockMvc
          .perform(
              post("/v1/statement-formats/1/unhide")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isNoContent());

      verify(statementFormatService).unhideFormat(1L, "usr_test123");
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

  @Nested
  class PdfWizard {

    @Test
    void analyzeReturnsRankedTableCandidates() throws Exception {
      when(pdfStatementFormatWizardService.analyze(any(byte[].class), eq("sample.pdf")))
          .thenReturn(
              new PdfWizardAnalysisResult(
                  List.of(
                      new PdfWizardTableCandidate(
                          "p1-l1-3",
                          1,
                          1,
                          3,
                          2,
                          0,
                          List.of("Date", "Description", "Amount"),
                          List.of(
                              List.of("Jan 1", "Coffee Shop", "$4.50"),
                              List.of("Jan 2", "Payment", "-$100.00")),
                          new PdfWizardColumnMapping(
                              "Date",
                              "MMM d",
                              "Description",
                              PdfWizardAmountMode.SIGNED_AMOUNT,
                              "Amount",
                              null,
                              null,
                              null,
                              PdfTextTableNegativeMeans.CREDIT),
                          0.91,
                          Map.of("dateHeader", 0.95),
                          List.of())),
                  0.91,
                  List.of()));

      mockMvc
          .perform(
              multipart("/v1/statement-formats/pdf-wizard/analyze")
                  .file(pdfFile())
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.candidates.length()").value(1))
          .andExpect(jsonPath("$.candidates[0].candidateId").value("p1-l1-3"))
          .andExpect(jsonPath("$.candidates[0].inferredMapping.dateHeader").value("Date"))
          .andExpect(jsonPath("$.candidates[0].inferredMapping.amountMode").value("SIGNED_AMOUNT"))
          .andExpect(jsonPath("$.confidence").value(0.91));
    }

    @Test
    void analyzeReturnsUnsupportedReasons() throws Exception {
      when(pdfStatementFormatWizardService.analyze(any(byte[].class), eq("sample.pdf")))
          .thenReturn(
              new PdfWizardAnalysisResult(
                  List.of(), 0.0, List.of("PDF does not contain enough extractable text.")));

      mockMvc
          .perform(
              multipart("/v1/statement-formats/pdf-wizard/analyze")
                  .file(pdfFile())
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.candidates.length()").value(0))
          .andExpect(
              jsonPath("$.rejectionReasons[0]")
                  .value("PDF does not contain enough extractable text."));
    }

    @Test
    void previewReturnsReadOnlyParsedRowsAndDiagnostics() throws Exception {
      when(pdfStatementFormatWizardService.preview(
              any(byte[].class), eq("sample.pdf"), any(PdfWizardMappingPreviewCommand.class)))
          .thenReturn(
              new PdfWizardPreviewResult(
                  List.of(
                      new PreviewTransaction(
                          LocalDate.parse("2025-01-02"),
                          "Coffee Shop",
                          new BigDecimal("4.50"),
                          TransactionType.DEBIT,
                          null,
                          "Example Bank",
                          "USD",
                          "checking-001")),
                  List.of("Matched a text-PDF table using 3 configured header token(s).")));

      mockMvc
          .perform(
              multipart("/v1/statement-formats/pdf-wizard/preview")
                  .file(pdfFile())
                  .file(jsonPart("request", pdfPreviewRequestJson()))
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.transactions.length()").value(1))
          .andExpect(jsonPath("$.transactions[0].description").value("Coffee Shop"))
          .andExpect(
              jsonPath("$.diagnostics[0]")
                  .value("Matched a text-PDF table using 3 configured header token(s)."));
    }

    @Test
    void saveCreatesPdfStatementFormat() throws Exception {
      var saved =
          StatementFormat.createUserPdfFormat("Example PDF", "Example Bank", "USD", "usr_test123");
      when(pdfStatementFormatWizardService.save(
              any(byte[].class),
              eq("sample.pdf"),
              any(PdfWizardSaveCommand.class),
              eq("usr_test123")))
          .thenReturn(saved);

      mockMvc
          .perform(
              multipart("/v1/statement-formats/pdf-wizard/save")
                  .file(pdfFile())
                  .file(jsonPart("request", pdfSaveRequestJson()))
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:write")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.displayName").value("Example PDF"))
          .andExpect(jsonPath("$.formatType").value("PDF"))
          .andExpect(jsonPath("$.bankName").value("Example Bank"));
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

  private MockMultipartFile pdfFile() {
    return new MockMultipartFile(
        "file", "sample.pdf", "application/pdf", "%PDF-1.4 sample".getBytes());
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

  private String pdfPreviewRequestJson() {
    return """
        {
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "accountId": "checking-001",
          "headerMustContain": ["Date", "Description", "Amount"],
          "minimumRows": 1,
          "yearSource": "EXPLICIT_DATE",
          "mapping": {
            "dateHeader": "Date",
            "dateFormat": "MM/dd/uuuu",
            "descriptionHeader": "Description",
            "amountMode": "SIGNED_AMOUNT",
            "amountHeader": "Amount",
            "negativeMeans": "CREDIT"
          }
        }
        """;
  }

  private String pdfSaveRequestJson() {
    return """
        {
          "displayName": "Example PDF",
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "headerMustContain": ["Date", "Description", "Amount"],
          "minimumRows": 1,
          "yearSource": "EXPLICIT_DATE",
          "mapping": {
            "dateHeader": "Date",
            "dateFormat": "MM/dd/uuuu",
            "descriptionHeader": "Description",
            "amountMode": "SIGNED_AMOUNT",
            "amountHeader": "Amount",
            "negativeMeans": "CREDIT"
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
