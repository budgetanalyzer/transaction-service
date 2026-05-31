package org.budgetanalyzer.transaction.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

import org.budgetanalyzer.transaction.service.dto.PdfTextTableNegativeMeans;
import org.budgetanalyzer.transaction.service.dto.PdfWizardAmountMode;
import org.budgetanalyzer.transaction.service.dto.PdfWizardColumnMapping;

/** Response DTO for inferred PDF wizard column mapping. */
@Schema(description = "PDF wizard column mapping")
public record PdfWizardColumnMappingResponse(
    String dateHeader,
    String dateFormat,
    String descriptionHeader,
    PdfWizardAmountMode amountMode,
    String amountHeader,
    String debitHeader,
    String creditHeader,
    String typeHeader,
    PdfTextTableNegativeMeans negativeMeans) {

  /** Creates a response from a service-layer PDF wizard mapping. */
  public static PdfWizardColumnMappingResponse from(PdfWizardColumnMapping pdfWizardColumnMapping) {
    if (pdfWizardColumnMapping == null) {
      return null;
    }
    return new PdfWizardColumnMappingResponse(
        pdfWizardColumnMapping.dateHeader(),
        pdfWizardColumnMapping.dateFormat(),
        pdfWizardColumnMapping.descriptionHeader(),
        pdfWizardColumnMapping.amountMode(),
        pdfWizardColumnMapping.amountHeader(),
        pdfWizardColumnMapping.debitHeader(),
        pdfWizardColumnMapping.creditHeader(),
        pdfWizardColumnMapping.typeHeader(),
        pdfWizardColumnMapping.negativeMeans());
  }
}
