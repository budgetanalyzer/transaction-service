package org.budgetanalyzer.transaction.service;

import java.util.List;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;

/**
 * Exception thrown when batch transaction validation fails.
 *
 * <p>This exception aggregates all business validation errors from a batch import operation. The
 * entire batch is rejected (all-or-nothing semantics) and each error includes the index, field, and
 * message for UI display.
 *
 * <p>This is distinct from Jakarta Bean Validation (400 VALIDATION_ERROR) which handles simple
 * field presence/format validation. BatchValidationException is for business rule violations that
 * require semantic understanding of the data.
 *
 * <p>Examples of business validation errors:
 *
 * <ul>
 *   <li>Transaction date is before year 2000 (not supported)
 *   <li>Transaction date is more than 1 day in the future
 *   <li>Amount is zero or negative
 * </ul>
 *
 * <p>This exception extends {@link BusinessException} and stores field errors using the parent's
 * fieldErrors support, ensuring consistent API response formatting with 422 status.
 */
public class BatchValidationException extends BusinessException {

  /**
   * Constructs a new batch validation exception with the specified validation errors.
   *
   * @param fieldErrors the list of field-level validation errors (with indices)
   */
  public BatchValidationException(List<FieldError> fieldErrors) {
    super(
        "Batch validation failed with " + fieldErrors.size() + " error(s)",
        BudgetAnalyzerError.BATCH_VALIDATION_FAILED.name(),
        fieldErrors);
  }
}
