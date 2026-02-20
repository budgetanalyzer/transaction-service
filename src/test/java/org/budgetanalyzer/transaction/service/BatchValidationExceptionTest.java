package org.budgetanalyzer.transaction.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;

class BatchValidationExceptionTest {

  @Test
  void extendsBusinessException() {
    var exception = new BatchValidationException(List.of());

    assertInstanceOf(BusinessException.class, exception);
  }

  @Test
  void hasCorrectErrorCode() {
    var exception = new BatchValidationException(List.of());

    assertEquals(BudgetAnalyzerError.BATCH_VALIDATION_FAILED.name(), exception.getCode());
  }

  @Test
  void containsFieldErrors() {
    var errors =
        List.of(
            FieldError.of(0, "amount", "Amount cannot be null", null),
            FieldError.of(2, "date", "Invalid date format", null));

    var exception = new BatchValidationException(errors);

    assertTrue(exception.hasFieldErrors());
    assertEquals(2, exception.getFieldErrors().size());
    assertEquals(0, exception.getFieldErrors().get(0).getIndex());
    assertEquals("amount", exception.getFieldErrors().get(0).getField());
    assertEquals("Amount cannot be null", exception.getFieldErrors().get(0).getMessage());
  }

  @Test
  void messageIncludesErrorCount() {
    var errors =
        List.of(
            FieldError.of(0, "amount", "Amount cannot be null", null),
            FieldError.of(2, "date", "Invalid date format", null));

    var exception = new BatchValidationException(errors);

    assertEquals("Batch validation failed with 2 error(s)", exception.getMessage());
  }
}
