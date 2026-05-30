package org.budgetanalyzer.transaction.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardValidationWarning;

/** Response DTO for a non-blocking CSV wizard warning. */
@Schema(description = "CSV wizard warning")
public record CsvWizardWarningResponse(String field, String message) {

  /** Creates a response from a service-layer warning. */
  public static CsvWizardWarningResponse from(
      CsvWizardValidationWarning csvWizardValidationWarning) {
    return new CsvWizardWarningResponse(
        csvWizardValidationWarning.field(), csvWizardValidationWarning.message());
  }
}
