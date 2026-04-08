package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.api.request.CreateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.request.UpdateStatementFormatRequest;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;
import org.budgetanalyzer.transaction.service.StatementFormatService;

@WebMvcTest(StatementFormatController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class StatementFormatControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StatementFormatService statementFormatService;

  @Nested
  class ListFormats {

    @Test
    void returnsAllFormats() throws Exception {
      var format1 = createCsvFormat("format-1", "Bank 1");
      var format2 = createCsvFormat("format-2", "Bank 2");
      when(statementFormatService.getAllFormats()).thenReturn(List.of(format1, format2));

      mockMvc
          .perform(
              get("/v1/statement-formats")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].formatKey").value("format-1"))
          .andExpect(jsonPath("$[0].bankName").value("Bank 1"))
          .andExpect(jsonPath("$[1].formatKey").value("format-2"));
    }

    @Test
    void returnsEmptyListWhenNoFormats() throws Exception {
      when(statementFormatService.getAllFormats()).thenReturn(List.of());

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
      var format = createCsvFormat("capital-one", "Capital One");
      setAuditFields(
          format,
          Instant.parse("2026-04-08T10:30:00Z"),
          Instant.parse("2026-04-08T10:45:00Z"),
          "usr_creator",
          "usr_updater");
      when(statementFormatService.getByFormatKey("capital-one")).thenReturn(format);

      mockMvc
          .perform(
              get("/v1/statement-formats/capital-one")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.formatKey").value("capital-one"))
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
      when(statementFormatService.getByFormatKey("unknown"))
          .thenThrow(new ResourceNotFoundException("Statement format not found with key: unknown"));

      mockMvc
          .perform(
              get("/v1/statement-formats/unknown")
                  .with(
                      ClaimsHeaderTestBuilder.user("usr_test123")
                          .withPermissions("statementformats:read")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.type").value("NOT_FOUND"))
          .andExpect(jsonPath("$.message").value("Statement format not found with key: unknown"));
    }
  }

  @Nested
  class CreateFormat {

    @Test
    void createsFormatSuccessfully() throws Exception {
      var format = createCsvFormat("new-format", "New Bank");
      when(statementFormatService.createFormat(any(CreateStatementFormatRequest.class)))
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
                      """))
          .andExpect(status().isCreated())
          .andExpect(header().exists("Location"))
          .andExpect(jsonPath("$.formatKey").value("new-format"))
          .andExpect(jsonPath("$.bankName").value("New Bank"));

      verify(statementFormatService).createFormat(any(CreateStatementFormatRequest.class));
    }

    @Test
    void returns422WhenFormatKeyExists() throws Exception {
      when(statementFormatService.createFormat(any(CreateStatementFormatRequest.class)))
          .thenThrow(
              new BusinessException(
                  "Format key already exists: existing",
                  BudgetAnalyzerError.FORMAT_KEY_ALREADY_EXISTS.name()));

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
                        "formatKey": "existing",
                        "displayName": "Bank - Export",
                        "formatType": "CSV",
                        "bankName": "Bank",
                        "defaultCurrencyIsoCode": "USD",
                        "dateHeader": "Date",
                        "dateFormat": "MM/dd/uu",
                        "descriptionHeader": "Description",
                        "creditHeader": "Amount"
                      }
                      """))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
          .andExpect(jsonPath("$.code").value("FORMAT_KEY_ALREADY_EXISTS"));
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
                        "formatKey": "",
                        "bankName": "Bank"
                      }
                      """))
          .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenInvalidFormatKeyPattern() throws Exception {
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
                        "formatKey": "INVALID_KEY",
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
      var updatedFormat = createCsvFormat("existing", "Updated Bank");
      when(statementFormatService.updateFormat(
              eq("existing"), any(UpdateStatementFormatRequest.class)))
          .thenReturn(updatedFormat);

      mockMvc
          .perform(
              put("/v1/statement-formats/existing")
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
          .andExpect(jsonPath("$.formatKey").value("existing"))
          .andExpect(jsonPath("$.bankName").value("Updated Bank"));

      verify(statementFormatService)
          .updateFormat(eq("existing"), any(UpdateStatementFormatRequest.class));
    }

    @Test
    void returns404WhenFormatNotFound() throws Exception {
      when(statementFormatService.updateFormat(
              eq("unknown"), any(UpdateStatementFormatRequest.class)))
          .thenThrow(new ResourceNotFoundException("Statement format not found with key: unknown"));

      mockMvc
          .perform(
              put("/v1/statement-formats/unknown")
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

  private StatementFormat createCsvFormat(String formatKey, String bankName) {
    return StatementFormat.createCsvFormat(
        formatKey,
        bankName + " - Export",
        bankName,
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        null,
        null);
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
