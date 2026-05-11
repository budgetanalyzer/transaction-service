package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractor;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

@ExtendWith(MockitoExtension.class)
class TransactionImportServiceTest {

  private static final String USER_ID = "user-123";

  @Mock private StatementExtractorRegistry extractorRegistry;

  @Mock private StatementExtractor statementExtractor;

  @Mock private TransactionRepository transactionRepository;

  @InjectMocks private TransactionImportService transactionImportService;

  @Test
  void previewFile_existingDatabaseDuplicate_marksTransactionWithExistingReason() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var duplicateKey = TransactionDuplicateKey.from(previewTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(previewTransaction));
    when(transactionRepository.findExistingDuplicateKeys(Set.of(duplicateKey), USER_ID))
        .thenReturn(Set.of(duplicateKey));

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).hasSize(1);
    assertThat(result.transactions().getFirst().duplicate()).isTrue();
    assertThat(result.transactions().getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.EXISTING_TRANSACTION);
    verify(transactionRepository).findExistingDuplicateKeys(Set.of(duplicateKey), USER_ID);
  }

  @Test
  void previewFile_inPreviewDuplicate_marksLaterTransactionWithInBatchReason() {
    var firstTransaction = previewTransaction("Coffee Shop");
    var secondTransaction = previewTransaction("Coffee Shop");
    var duplicateKey = TransactionDuplicateKey.from(firstTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(firstTransaction, secondTransaction));
    when(transactionRepository.findExistingDuplicateKeys(Set.of(duplicateKey), USER_ID))
        .thenReturn(Set.of());

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).hasSize(2);
    assertThat(result.transactions().get(0).duplicate()).isFalse();
    assertThat(result.transactions().get(0).duplicateReason()).isNull();
    assertThat(result.transactions().get(1).duplicate()).isTrue();
    assertThat(result.transactions().get(1).duplicateReason())
        .isEqualTo(PreviewDuplicateReason.IN_BATCH);
  }

  @Test
  void previewFile_emptyExtraction_doesNotQueryDuplicateKeys() {
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(statementExtractor.extract(any(byte[].class), eq("checking"))).thenReturn(List.of());

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).isEmpty();
    verify(transactionRepository, never()).findExistingDuplicateKeys(any(), any());
  }

  private static PreviewTransaction previewTransaction(String description) {
    return new PreviewTransaction(
        LocalDate.of(2024, 1, 15),
        description,
        new BigDecimal("4.50"),
        TransactionType.DEBIT,
        null,
        "Test Bank",
        "USD",
        "checking");
  }

  private static MockMultipartFile multipartFile() {
    return new MockMultipartFile(
        "file",
        "transactions.csv",
        "text/csv",
        "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50".getBytes());
  }
}
