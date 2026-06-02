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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.service.exception.ResourceNotFoundException;
import org.budgetanalyzer.transaction.domain.FormatType;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.StatementFormatScope;
import org.budgetanalyzer.transaction.domain.StatementFormatUserPreference;
import org.budgetanalyzer.transaction.repository.ParserRevisionRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatRepository;
import org.budgetanalyzer.transaction.repository.StatementFormatUserPreferenceRepository;
import org.budgetanalyzer.transaction.service.dto.StatementFormatCommand;
import org.budgetanalyzer.transaction.service.dto.StatementFormatPatch;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

@ExtendWith(MockitoExtension.class)
class StatementFormatServiceTest {

  @Mock private StatementFormatRepository statementFormatRepository;
  @Mock private StatementFormatUserPreferenceRepository statementFormatUserPreferenceRepository;
  @Mock private ParserRevisionRepository parserRevisionRepository;
  @Mock private StatementExtractorRegistry statementExtractorRegistry;

  private StatementFormatService statementFormatService;

  @BeforeEach
  void setUp() {
    statementFormatService =
        new StatementFormatService(
            statementFormatRepository,
            statementFormatUserPreferenceRepository,
            parserRevisionRepository,
            statementExtractorRegistry,
            new ObjectMapper().findAndRegisterModules());
  }

  @Nested
  class Visibility {

    @Test
    void listFormatsReturnsAllFormatsWhenReadAnyAllowed() {
      var systemFormat =
          StatementFormat.createSystemPdfFormat("System Format", "System Bank", "USD");
      setId(systemFormat, 1L);
      when(statementFormatRepository.findAll()).thenReturn(List.of(systemFormat));
      when(statementFormatUserPreferenceRepository.findHiddenStatementFormatIdsByUserId(
              "usr_owner"))
          .thenReturn(List.of());

      var result = statementFormatService.listFormats("usr_owner", true, false);

      assertThat(result).extracting("statementFormat").containsExactly(systemFormat);
    }

    @Test
    void listFormatsReturnsUserVisibleFormatsWhenReadAnyNotAllowed() {
      var userFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      setId(userFormat, 1L);
      when(statementFormatRepository.findVisibleToUser("usr_owner"))
          .thenReturn(List.of(userFormat));
      when(statementFormatUserPreferenceRepository.findHiddenStatementFormatIdsByUserId(
              "usr_owner"))
          .thenReturn(List.of());

      var result = statementFormatService.listFormats("usr_owner", false, false);

      assertThat(result).extracting("statementFormat").containsExactly(userFormat);
    }

    @Test
    void listFormatsExcludesHiddenFormatsByDefault() {
      var visibleFormat =
          StatementFormat.createCsvFormat("Visible Format", "Visible Bank", "USD", "usr_owner");
      var hiddenFormat =
          StatementFormat.createCsvFormat("Hidden Format", "Hidden Bank", "USD", "usr_owner");
      setId(visibleFormat, 1L);
      setId(hiddenFormat, 2L);
      when(statementFormatRepository.findVisibleToUser("usr_owner"))
          .thenReturn(List.of(visibleFormat, hiddenFormat));
      when(statementFormatUserPreferenceRepository.findHiddenStatementFormatIdsByUserId(
              "usr_owner"))
          .thenReturn(List.of(2L));

      var result = statementFormatService.listFormats("usr_owner", false, false);

      assertThat(result).extracting("statementFormat").containsExactly(visibleFormat);
      assertThat(result).extracting("hidden").containsExactly(false);
    }

    @Test
    void listFormatsIncludesHiddenFormatsWhenRequested() {
      var visibleFormat =
          StatementFormat.createCsvFormat("Visible Format", "Visible Bank", "USD", "usr_owner");
      var hiddenFormat =
          StatementFormat.createCsvFormat("Hidden Format", "Hidden Bank", "USD", "usr_owner");
      setId(visibleFormat, 1L);
      setId(hiddenFormat, 2L);
      when(statementFormatRepository.findVisibleToUser("usr_owner"))
          .thenReturn(List.of(visibleFormat, hiddenFormat));
      when(statementFormatUserPreferenceRepository.findHiddenStatementFormatIdsByUserId(
              "usr_owner"))
          .thenReturn(List.of(2L));

      var result = statementFormatService.listFormats("usr_owner", false, true);

      assertThat(result).extracting("statementFormat").containsExactly(visibleFormat, hiddenFormat);
      assertThat(result).extracting("hidden").containsExactly(false, true);
    }

    @Test
    void getByIdUsesVisibleLookupWithoutReadAny() {
      var userFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(userFormat));

      var result = statementFormatService.getById(7L, "usr_owner", false);

      assertThat(result).isSameAs(userFormat);
    }

    @Test
    void getByIdThrowsWhenFormatIsNotVisible() {
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> statementFormatService.getById(7L, "usr_owner", false))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Statement format not found with id: 7");
    }
  }

  @Nested
  class UserPreference {

    @Test
    void hideFormatCreatesHiddenPreferenceWhenNoPreferenceExists() {
      var statementFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormat));
      when(statementFormatUserPreferenceRepository.findByStatementFormatIdAndUserId(
              7L, "usr_owner"))
          .thenReturn(Optional.empty());

      statementFormatService.hideFormat(7L, "usr_owner");

      var preferenceCaptor = ArgumentCaptor.forClass(StatementFormatUserPreference.class);
      verify(statementFormatUserPreferenceRepository).save(preferenceCaptor.capture());
      assertThat(preferenceCaptor.getValue().getStatementFormat()).isSameAs(statementFormat);
      assertThat(preferenceCaptor.getValue().getUserId()).isEqualTo("usr_owner");
      assertThat(preferenceCaptor.getValue().isHidden()).isTrue();
    }

    @Test
    void hideFormatCreatesHiddenPreferenceForSystemFormat() {
      var statementFormat =
          StatementFormat.createSystemPdfFormat("System Format", "System Bank", "USD");
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormat));
      when(statementFormatUserPreferenceRepository.findByStatementFormatIdAndUserId(
              7L, "usr_owner"))
          .thenReturn(Optional.empty());

      statementFormatService.hideFormat(7L, "usr_owner");

      var preferenceCaptor = ArgumentCaptor.forClass(StatementFormatUserPreference.class);
      verify(statementFormatUserPreferenceRepository).save(preferenceCaptor.capture());
      assertThat(preferenceCaptor.getValue().getStatementFormat()).isSameAs(statementFormat);
      assertThat(preferenceCaptor.getValue().getUserId()).isEqualTo("usr_owner");
      assertThat(preferenceCaptor.getValue().isHidden()).isTrue();
    }

    @Test
    void hideFormatUpdatesExistingPreferenceToHidden() {
      var statementFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      var statementFormatUserPreference =
          StatementFormatUserPreference.createHidden(statementFormat, "usr_owner");
      statementFormatUserPreference.setHidden(false);
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormat));
      when(statementFormatUserPreferenceRepository.findByStatementFormatIdAndUserId(
              7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormatUserPreference));

      statementFormatService.hideFormat(7L, "usr_owner");

      assertThat(statementFormatUserPreference.isHidden()).isTrue();
      verify(statementFormatUserPreferenceRepository).save(statementFormatUserPreference);
    }

    @Test
    void hideFormatIsIdempotentWhenPreferenceIsAlreadyHidden() {
      var statementFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      var statementFormatUserPreference =
          StatementFormatUserPreference.createHidden(statementFormat, "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormat));
      when(statementFormatUserPreferenceRepository.findByStatementFormatIdAndUserId(
              7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormatUserPreference));

      statementFormatService.hideFormat(7L, "usr_owner");

      assertThat(statementFormatUserPreference.isHidden()).isTrue();
      verify(statementFormatUserPreferenceRepository).save(statementFormatUserPreference);
    }

    @Test
    void unhideFormatUpdatesExistingPreferenceToVisible() {
      var statementFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      var statementFormatUserPreference =
          StatementFormatUserPreference.createHidden(statementFormat, "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormat));
      when(statementFormatUserPreferenceRepository.findByStatementFormatIdAndUserId(
              7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormatUserPreference));

      statementFormatService.unhideFormat(7L, "usr_owner");

      assertThat(statementFormatUserPreference.isHidden()).isFalse();
      verify(statementFormatUserPreferenceRepository).save(statementFormatUserPreference);
    }

    @Test
    void unhideFormatIsNoOpWhenPreferenceDoesNotExist() {
      var statementFormat =
          StatementFormat.createCsvFormat("User Format", "User Bank", "USD", "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.of(statementFormat));
      when(statementFormatUserPreferenceRepository.findByStatementFormatIdAndUserId(
              7L, "usr_owner"))
          .thenReturn(Optional.empty());

      statementFormatService.unhideFormat(7L, "usr_owner");

      verify(statementFormatUserPreferenceRepository, never()).save(any());
    }

    @Test
    void hideFormatThrowsWhenFormatIsNotVisible() {
      when(statementFormatRepository.findVisibleToUserById(7L, "usr_owner"))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> statementFormatService.hideFormat(7L, "usr_owner"))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Statement format not found with id: 7");

      verify(statementFormatUserPreferenceRepository, never()).save(any());
    }
  }

  @Nested
  class CreateFormat {

    @Test
    void createsCsvFormatWithParserRevision() {
      var command =
          new StatementFormatCommand(
              "Test Bank - CSV",
              FormatType.CSV,
              "Test Bank",
              "USD",
              null,
              "Date",
              "MM/dd/uu",
              "Description",
              "Amount",
              "Amount",
              null,
              null);
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var result = statementFormatService.createFormat(command, "usr_owner", false);

      assertThat(result.getFormatType()).isEqualTo(FormatType.CSV);
      assertThat(result.getScope()).isEqualTo(StatementFormatScope.USER);
      assertThat(result.getOwnerId()).isEqualTo("usr_owner");
      verify(parserRevisionRepository).save(any());
      verify(statementExtractorRegistry).refreshCsvExtractors();
    }

    @Test
    void createsSystemCsvFormatWhenWriteAnyAllowed() {
      var command =
          new StatementFormatCommand(
              "System Bank",
              FormatType.CSV,
              "System Bank",
              "usd",
              StatementFormatScope.SYSTEM,
              "Date",
              "MM/dd/uu",
              "Description",
              "Amount",
              "Amount",
              null,
              null);
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var result = statementFormatService.createFormat(command, "usr_admin", true);

      assertThat(result.getScope()).isEqualTo(StatementFormatScope.SYSTEM);
      assertThat(result.getOwnerId()).isNull();
      assertThat(result.getDefaultCurrencyIsoCode()).isEqualTo("USD");
      verify(parserRevisionRepository).save(any());
      verify(statementExtractorRegistry).refreshCsvExtractors();
    }

    @Test
    void rejectsNonCsvFormatsBecauseTheyCannotCreateParserRevisions() {
      var command =
          new StatementFormatCommand(
              "System Bank",
              FormatType.PDF,
              "System Bank",
              "USD",
              StatementFormatScope.SYSTEM,
              null,
              null,
              null,
              null,
              null,
              null,
              null);

      assertThatThrownBy(() -> statementFormatService.createFormat(command, "usr_admin", true))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              exception ->
                  assertThat(((BusinessException) exception).getFieldErrors())
                      .extracting("field")
                      .contains("formatType"));

      verify(statementFormatRepository, never()).save(any());
    }

    @Test
    void rejectsCsvFormatsMissingRequiredParserColumns() {
      var command =
          new StatementFormatCommand(
              "Test Bank - CSV",
              FormatType.CSV,
              "Test Bank",
              "USD",
              null,
              "Date",
              "MM/dd/uu",
              "Description",
              "Amount",
              null,
              null,
              null);

      assertThatThrownBy(() -> statementFormatService.createFormat(command, "usr_owner", false))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              exception ->
                  assertThat(((BusinessException) exception).getFieldErrors())
                      .extracting("field")
                      .contains("debitHeader"));

      verify(statementFormatRepository, never()).save(any());
    }

    @Test
    void rejectsSystemFormatWithoutWriteAny() {
      var command =
          new StatementFormatCommand(
              "System Bank",
              FormatType.CSV,
              "System Bank",
              "USD",
              StatementFormatScope.SYSTEM,
              "Date",
              "MM/dd/uu",
              "Description",
              "Amount",
              "Amount",
              null,
              null);

      assertThatThrownBy(() -> statementFormatService.createFormat(command, "usr_owner", false))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("statementformats:write:any");

      verify(statementFormatRepository, never()).save(any());
    }
  }

  @Nested
  class UpdateFormat {

    @Test
    void updatesWritableUserFormatById() {
      var existingFormat =
          StatementFormat.createCsvFormat("Existing CSV", "Original Bank", "USD", "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(9L, "usr_owner"))
          .thenReturn(Optional.of(existingFormat));
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var patch = new StatementFormatPatch(null, "Updated Bank", "EUR", false);

      var result = statementFormatService.updateFormat(9L, patch, "usr_owner", false);

      assertThat(result.getBankName()).isEqualTo("Updated Bank");
      assertThat(result.getDefaultCurrencyIsoCode()).isEqualTo("EUR");
      assertThat(result.isEnabled()).isFalse();
      verify(statementExtractorRegistry).refreshCsvExtractors();
    }

    @Test
    void rejectsSystemFormatUpdateWithoutWriteAny() {
      var systemFormat = StatementFormat.createSystemPdfFormat("System PDF", "System Bank", "USD");
      when(statementFormatRepository.findVisibleToUserById(9L, "usr_owner"))
          .thenReturn(Optional.of(systemFormat));

      var patch = new StatementFormatPatch("Updated", null, null, null);

      assertThatThrownBy(() -> statementFormatService.updateFormat(9L, patch, "usr_owner", false))
          .isInstanceOf(ResourceNotFoundException.class);

      verify(statementFormatRepository, never()).save(any());
    }

    @Test
    void doesNotRefreshExtractorsForPdfFormat() {
      var pdfFormat = StatementFormat.createUserPdfFormat("Bank PDF", "Bank", "USD", "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(9L, "usr_owner"))
          .thenReturn(Optional.of(pdfFormat));
      when(statementFormatRepository.save(any(StatementFormat.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      var patch = new StatementFormatPatch(null, "Updated Bank", null, null);

      statementFormatService.updateFormat(9L, patch, "usr_owner", false);

      verify(statementExtractorRegistry, never()).refreshCsvExtractors();
    }

    @Test
    void rejectsBlankMetadataPatch() {
      var existingFormat =
          StatementFormat.createCsvFormat("Existing CSV", "Original Bank", "USD", "usr_owner");
      when(statementFormatRepository.findVisibleToUserById(9L, "usr_owner"))
          .thenReturn(Optional.of(existingFormat));

      var patch = new StatementFormatPatch(" ", null, null, null);

      assertThatThrownBy(() -> statementFormatService.updateFormat(9L, patch, "usr_owner", false))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              exception ->
                  assertThat(((BusinessException) exception).getFieldErrors())
                      .extracting("field")
                      .contains("displayName"));

      verify(statementFormatRepository, never()).save(any());
    }
  }

  private void setId(StatementFormat statementFormat, Long id) {
    try {
      var idField = StatementFormat.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(statementFormat, id);
    } catch (ReflectiveOperationException reflectiveOperationException) {
      throw new IllegalStateException(
          "Failed to set statement format ID", reflectiveOperationException);
    }
  }
}
