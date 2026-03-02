package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.api.request.CreateStatementFormatRequest;
import org.budgetanalyzer.transaction.api.request.UpdateStatementFormatRequest;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

@ExtendWith(MockitoExtension.class)
class StatementFormatServiceTest {

  @Mock private StatementFormatRepository statementFormatRepository;
  @Mock private StatementExtractorRegistry statementExtractorRegistry;

  private StatementFormatService statementFormatService;

  @BeforeEach
  void setUp() {
    statementFormatService =
        new StatementFormatService(statementFormatRepository, statementExtractorRegistry);
  }

  @Nested
  class GetAllFormats {

    @Test
    void returnsAllFormats() {
      var format1 = createCsvFormat("format-1");
      var format2 = createCsvFormat("format-2");
      when(statementFormatRepository.findAll()).thenReturn(List.of(format1, format2));

      var result = statementFormatService.getAllFormats();

      assertThat(result).hasSize(2);
      assertThat(result)
          .extracting(StatementFormat::getFormatKey)
          .containsExactly("format-1", "format-2");
    }

    @Test
    void returnsEmptyListWhenNoFormats() {
      when(statementFormatRepository.findAll()).thenReturn(List.of());

      var result = statementFormatService.getAllFormats();

      assertThat(result).isEmpty();
    }
  }

  @Nested
  class GetByFormatKey {

    @Test
    void returnsFormatWhenFound() {
      var format = createCsvFormat("test-format");
      when(statementFormatRepository.findByFormatKey("test-format"))
          .thenReturn(Optional.of(format));

      var result = statementFormatService.getByFormatKey("test-format");

      assertThat(result.getFormatKey()).isEqualTo("test-format");
    }

    @Test
    void throwsResourceNotFoundExceptionWhenNotFound() {
      when(statementFormatRepository.findByFormatKey("unknown")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> statementFormatService.getByFormatKey("unknown"))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Statement format not found with key: unknown");
    }
  }

  @Nested
  class CreateFormat {

    @Test
    void createsCsvFormatSuccessfully() {
      var request = createCsvFormatRequest("new-format");
      when(statementFormatRepository.existsByFormatKey("new-format")).thenReturn(false);
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var result = statementFormatService.createFormat(request);

      assertThat(result.getFormatKey()).isEqualTo("new-format");
      assertThat(result.getFormatType()).isEqualTo(FormatType.CSV);
      assertThat(result.getBankName()).isEqualTo("Test Bank");
      verify(statementExtractorRegistry).refreshCsvExtractors();
    }

    @Test
    void createsPdfFormatSuccessfully() {
      var request = createPdfFormatRequest("new-pdf-format");
      when(statementFormatRepository.existsByFormatKey("new-pdf-format")).thenReturn(false);
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var result = statementFormatService.createFormat(request);

      assertThat(result.getFormatKey()).isEqualTo("new-pdf-format");
      assertThat(result.getFormatType()).isEqualTo(FormatType.PDF);
      verify(statementExtractorRegistry, never()).refreshCsvExtractors();
    }

    @Test
    void throwsBusinessExceptionWhenFormatKeyExists() {
      var request = createCsvFormatRequest("existing-format");
      when(statementFormatRepository.existsByFormatKey("existing-format")).thenReturn(true);

      assertThatThrownBy(() -> statementFormatService.createFormat(request))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("Format key already exists: existing-format");

      verify(statementFormatRepository, never()).save(any());
    }

    @Test
    void savesCsvFormatWithAllFields() {
      var request =
          new CreateStatementFormatRequest(
              "full-csv",
              "Full Bank - Export",
              FormatType.CSV,
              "Full Bank",
              "EUR",
              "Date Col",
              "dd/MM/yyyy",
              "Desc Col",
              "Credit Col",
              "Debit Col",
              "Type Col",
              "Category Col");
      when(statementFormatRepository.existsByFormatKey("full-csv")).thenReturn(false);
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var result = statementFormatService.createFormat(request);

      assertThat(result.getDateHeader()).isEqualTo("Date Col");
      assertThat(result.getDateFormat()).isEqualTo("dd/MM/yyyy");
      assertThat(result.getDescriptionHeader()).isEqualTo("Desc Col");
      assertThat(result.getCreditHeader()).isEqualTo("Credit Col");
      assertThat(result.getDebitHeader()).isEqualTo("Debit Col");
      assertThat(result.getTypeHeader()).isEqualTo("Type Col");
      assertThat(result.getCategoryHeader()).isEqualTo("Category Col");
    }
  }

  @Nested
  class UpdateFormat {

    @Test
    void updatesFormatSuccessfully() {
      var existingFormat = createCsvFormat("existing");
      when(statementFormatRepository.findByFormatKey("existing"))
          .thenReturn(Optional.of(existingFormat));
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var request =
          new UpdateStatementFormatRequest(
              null, "Updated Bank", "EUR", null, null, null, null, null, null, null, null);

      var result = statementFormatService.updateFormat("existing", request);

      assertThat(result.getBankName()).isEqualTo("Updated Bank");
      assertThat(result.getDefaultCurrencyIsoCode()).isEqualTo("EUR");
      verify(statementExtractorRegistry).refreshCsvExtractors();
    }

    @Test
    void updatesOnlyProvidedFields() {
      var existingFormat = createCsvFormat("existing");
      existingFormat.setBankName("Original Bank");
      existingFormat.setDefaultCurrencyIsoCode("USD");
      when(statementFormatRepository.findByFormatKey("existing"))
          .thenReturn(Optional.of(existingFormat));
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var request =
          new UpdateStatementFormatRequest(
              null, "New Bank", null, null, null, null, null, null, null, null, null);

      var result = statementFormatService.updateFormat("existing", request);

      assertThat(result.getBankName()).isEqualTo("New Bank");
      assertThat(result.getDefaultCurrencyIsoCode()).isEqualTo("USD");
    }

    @Test
    void updatesEnabledStatus() {
      var existingFormat = createCsvFormat("existing");
      when(statementFormatRepository.findByFormatKey("existing"))
          .thenReturn(Optional.of(existingFormat));
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var request =
          new UpdateStatementFormatRequest(
              null, null, null, null, null, null, null, null, null, null, false);

      var result = statementFormatService.updateFormat("existing", request);

      assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void throwsResourceNotFoundWhenFormatDoesNotExist() {
      when(statementFormatRepository.findByFormatKey("unknown")).thenReturn(Optional.empty());

      var request =
          new UpdateStatementFormatRequest(
              null, "Bank", null, null, null, null, null, null, null, null, null);

      assertThatThrownBy(() -> statementFormatService.updateFormat("unknown", request))
          .isInstanceOf(ResourceNotFoundException.class);

      verify(statementFormatRepository, never()).save(any());
    }

    @Test
    void doesNotRefreshExtractorsForPdfFormat() {
      var pdfFormat = StatementFormat.createPdfFormat("pdf-format", "Bank - PDF", "Bank", "USD");
      when(statementFormatRepository.findByFormatKey("pdf-format"))
          .thenReturn(Optional.of(pdfFormat));
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var request =
          new UpdateStatementFormatRequest(
              null, "Updated Bank", null, null, null, null, null, null, null, null, null);

      statementFormatService.updateFormat("pdf-format", request);

      verify(statementExtractorRegistry, never()).refreshCsvExtractors();
    }
  }

  @Nested
  class DisableFormat {

    @Test
    void disablesFormatSuccessfully() {
      var format = createCsvFormat("to-disable");
      when(statementFormatRepository.findByFormatKey("to-disable")).thenReturn(Optional.of(format));

      statementFormatService.disableFormat("to-disable");

      var captor = ArgumentCaptor.forClass(StatementFormat.class);
      verify(statementFormatRepository).save(captor.capture());
      assertThat(captor.getValue().isEnabled()).isFalse();
      verify(statementExtractorRegistry).refreshCsvExtractors();
    }

    @Test
    void throwsResourceNotFoundWhenFormatDoesNotExist() {
      when(statementFormatRepository.findByFormatKey("unknown")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> statementFormatService.disableFormat("unknown"))
          .isInstanceOf(ResourceNotFoundException.class);

      verify(statementFormatRepository, never()).save(any());
    }

    @Test
    void doesNotRefreshExtractorsForPdfFormat() {
      var pdfFormat = StatementFormat.createPdfFormat("pdf-format", "Bank - PDF", "Bank", "USD");
      when(statementFormatRepository.findByFormatKey("pdf-format"))
          .thenReturn(Optional.of(pdfFormat));

      statementFormatService.disableFormat("pdf-format");

      verify(statementExtractorRegistry, never()).refreshCsvExtractors();
    }
  }

  private StatementFormat createCsvFormat(String formatKey) {
    return StatementFormat.createCsvFormat(
        formatKey,
        "Test Bank - Export",
        "Test Bank",
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        null,
        null);
  }

  private CreateStatementFormatRequest createCsvFormatRequest(String formatKey) {
    return new CreateStatementFormatRequest(
        formatKey,
        "Test Bank - Export",
        FormatType.CSV,
        "Test Bank",
        "USD",
        "Date",
        "MM/dd/uu",
        "Description",
        "Amount",
        "Amount",
        null,
        null);
  }

  private CreateStatementFormatRequest createPdfFormatRequest(String formatKey) {
    return new CreateStatementFormatRequest(
        formatKey,
        "Test Bank - PDF",
        FormatType.PDF,
        "Test Bank",
        "USD",
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
