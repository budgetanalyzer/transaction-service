package org.budgetanalyzer.transaction.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.CsvWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.CsvWizardColumnMapping;

/** Response DTO for inferred or confirmed CSV wizard column mapping. */
@Schema(description = "CSV wizard column mapping")
public record CsvWizardColumnMappingResponse(
    String dateColumn,
    String dateFormat,
    String descriptionColumn,
    CsvWizardAmountMode amountMode,
    String amountColumn,
    String debitColumn,
    String creditColumn,
    String typeColumn,
    String categoryColumn) {

  /** Creates a response from a service-layer mapping. */
  public static CsvWizardColumnMappingResponse from(CsvWizardColumnMapping csvWizardColumnMapping) {
    if (csvWizardColumnMapping == null) {
      return null;
    }
    return new CsvWizardColumnMappingResponse(
        csvWizardColumnMapping.dateColumn(),
        csvWizardColumnMapping.dateFormat(),
        csvWizardColumnMapping.descriptionColumn(),
        csvWizardColumnMapping.amountMode(),
        csvWizardColumnMapping.amountColumn(),
        csvWizardColumnMapping.debitColumn(),
        csvWizardColumnMapping.creditColumn(),
        csvWizardColumnMapping.typeColumn(),
        csvWizardColumnMapping.categoryColumn());
  }
}
