package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.budgetanalyzer.service.api.FieldError;
import org.budgetanalyzer.service.exception.BusinessException;

class BatchValidationExceptionTest {

  @Test
  void extendsBusinessException() {
    var exception = new BatchValidationException(List.of());

    assertThat(exception).isInstanceOf(BusinessException.class);
  }

  @Test
  void hasCorrectErrorCode() {
    var exception = new BatchValidationException(List.of());

    assertThat(exception.getCode()).isEqualTo(BudgetAnalyzerError.BATCH_VALIDATION_FAILED.name());
  }

  @Test
  void containsStructuredFieldErrors() {
    var errors =
        List.of(
            FieldError.forIndexedField(0, "amount", "Amount cannot be null", null),
            FieldError.forIndexedField(2, "date", "Invalid date format", "not-a-date"));

    var exception = new BatchValidationException(errors);

    assertThat(exception.hasFieldErrors()).isTrue();
    assertThat(exception.getFieldErrors())
        .hasSize(2)
        .satisfiesExactly(
            fieldError -> {
              assertThat(fieldError.getIndex()).isZero();
              assertThat(fieldError.getField()).isEqualTo("amount");
              assertThat(fieldError.getRejectedValue()).isNull();
            },
            fieldError -> {
              assertThat(fieldError.getIndex()).isEqualTo(2);
              assertThat(fieldError.getField()).isEqualTo("date");
              assertThat(fieldError.getRejectedValue()).isEqualTo("not-a-date");
            });
  }

  @Test
  void fieldErrorsAreDefensivelyCopiedAndUnmodifiable() {
    var errors = new ArrayList<FieldError>();
    errors.add(FieldError.forIndexedField(0, "amount", "Amount cannot be null", null));

    var exception = new BatchValidationException(errors);
    errors.clear();

    assertThat(exception.getFieldErrors()).hasSize(1);
    assertThatThrownBy(
            () ->
                exception
                    .getFieldErrors()
                    .add(FieldError.forIndexedField(1, "date", "Invalid date format", null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
