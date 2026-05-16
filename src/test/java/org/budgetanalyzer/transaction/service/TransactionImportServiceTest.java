package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository.TransactionDuplicateCandidate;
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

  @Mock private FileImportTrackingService fileImportTrackingService;

  @Mock private PreviewImportTokenService previewImportTokenService;

  @InjectMocks private TransactionImportService transactionImportService;

  @Test
  void previewFile_existingDatabaseDuplicate_marksTransactionWithExistingReason() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateCandidateKey.from(previewTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(previewTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(List.of(duplicateCandidate(candidateKey, 1L, "Coffee Shop")));

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.fileImport().alreadyImported()).isFalse();
    assertThat(result.previewImportToken()).isEqualTo("preview-token");
    assertThat(result.fileImport().warningCode()).isNull();
    assertThat(result.fileImport().previousImport()).isNull();
    assertThat(result.transactions()).hasSize(1);
    assertThat(result.transactions().getFirst().duplicate()).isTrue();
    assertThat(result.transactions().getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.EXISTING_TRANSACTION);
    verify(transactionRepository).findDuplicateCandidates(Set.of(candidateKey), USER_ID);
  }

  @Test
  void previewFile_existingFuzzyDatabaseDuplicate_marksTransactionWithExistingReason() {
    var previewTransaction = previewTransaction("X CORP. PAID FEATURESBASTROPTX");
    var candidateKey = TransactionDuplicateCandidateKey.from(previewTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(previewTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(
            List.of(duplicateCandidate(candidateKey, 42L, "X CORP. PAID FEATURES BASTROP     TX")));

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).hasSize(1);
    assertThat(result.transactions().getFirst().duplicate()).isTrue();
    assertThat(result.transactions().getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.EXISTING_TRANSACTION);
  }

  @Test
  void previewFile_existingCandidateWithDifferentDescription_doesNotMarkDuplicate() {
    var previewTransaction = previewTransaction("Rent Payment May");
    var candidateKey = TransactionDuplicateCandidateKey.from(previewTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(previewTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(List.of(duplicateCandidate(candidateKey, 43L, "Starbucks Store 1234")));

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).hasSize(1);
    assertThat(result.transactions().getFirst().duplicate()).isFalse();
    assertThat(result.transactions().getFirst().duplicateReason()).isNull();
  }

  @Test
  void previewFile_inPreviewDuplicate_marksLaterTransactionWithInBatchReason() {
    var firstTransaction = previewTransaction("Coffee Shop");
    var secondTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateCandidateKey.from(firstTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(firstTransaction, secondTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(List.of());

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
  void previewFile_inPreviewFuzzyDuplicate_marksLaterTransactionWithInBatchReason() {
    var firstTransaction = previewTransaction("X CORP. PAID FEATURES BASTROP     TX");
    var secondTransaction = previewTransaction("X CORP. PAID FEATURESBASTROPTX");
    var candidateKey = TransactionDuplicateCandidateKey.from(firstTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(firstTransaction, secondTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(List.of());

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
  void previewFile_inPreviewCandidateWithDifferentDescription_doesNotMarkInBatchDuplicate() {
    var firstTransaction = previewTransaction("Rent Payment May");
    var secondTransaction = previewTransaction("Starbucks Store 1234");
    var candidateKey = TransactionDuplicateCandidateKey.from(firstTransaction).toLookupValue();
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(firstTransaction, secondTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).hasSize(2);
    assertThat(result.transactions().get(0).duplicate()).isFalse();
    assertThat(result.transactions().get(0).duplicateReason()).isNull();
    assertThat(result.transactions().get(1).duplicate()).isFalse();
    assertThat(result.transactions().get(1).duplicateReason()).isNull();
  }

  @Test
  void previewFile_emptyExtraction_doesNotQueryDuplicateKeys() {
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking"))).thenReturn(List.of());

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.transactions()).isEmpty();
    verify(transactionRepository, never()).findDuplicateCandidates(any(), any());
  }

  @Test
  void previewFile_duplicateLookupFailure_surfacesInfrastructureException() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateCandidateKey.from(previewTransaction).toLookupValue();
    var multipartFile = multipartFile();
    var dataAccessException = new DataAccessResourceFailureException("database unavailable");

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(previewTransaction));
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenThrow(dataAccessException);

    assertThatThrownBy(
            () ->
                transactionImportService.previewFile(
                    "capital-one", "checking", multipartFile, USER_ID))
        .isSameAs(dataAccessException);
  }

  @Test
  void previewFile_existingFileImport_populatesFileImportWarning() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var fileImport =
        FileImport.create("hash", "transactions.csv", "capital-one", "checking", 64L, 12, USER_ID);
    var multipartFile = multipartFile();

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.of(fileImport)));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking")))
        .thenReturn(List.of(previewTransaction));
    when(transactionRepository.findDuplicateCandidates(any(), eq(USER_ID))).thenReturn(List.of());

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.fileImport().alreadyImported()).isTrue();
    assertThat(result.fileImport().warningCode().name()).isEqualTo("FILE_ALREADY_IMPORTED");
    assertThat(result.fileImport().previousImport().originalFilename())
        .isEqualTo("transactions.csv");
    assertThat(result.fileImport().previousImport().importedAt())
        .isEqualTo(fileImport.getImportedAt());
    assertThat(result.fileImport().previousImport().format()).isEqualTo("capital-one");
    assertThat(result.fileImport().previousImport().accountId()).isEqualTo("checking");
    assertThat(result.fileImport().previousImport().transactionCount()).isEqualTo(12);
  }

  @Test
  void previewFile_nullOriginalFilename_rejectsBeforeReadingFile() throws Exception {
    var multipartFile = spy(multipartFile(null));

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFile(
                    "capital-one", "checking", multipartFile, USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verifyNoInteractions(fileImportTrackingService, previewImportTokenService);
    verify(multipartFile, never()).getBytes();
    verify(statementExtractor, never()).extract(any(byte[].class), any());
  }

  @Test
  void previewFile_blankOriginalFilename_rejectsBeforeReadingFile() throws Exception {
    var multipartFile = spy(multipartFile("   "));

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFile(
                    "capital-one", "checking", multipartFile, USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verifyNoInteractions(fileImportTrackingService, previewImportTokenService);
    verify(multipartFile, never()).getBytes();
    verify(statementExtractor, never()).extract(any(byte[].class), any());
  }

  @Test
  void previewFile_emptyOriginalFilename_rejectsBeforeReadingFile() throws Exception {
    var multipartFile = spy(multipartFile(""));

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFile(
                    "capital-one", "checking", multipartFile, USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verifyNoInteractions(fileImportTrackingService, previewImportTokenService);
    verify(multipartFile, never()).getBytes();
    verify(statementExtractor, never()).extract(any(byte[].class), any());
  }

  @Test
  void previewFile_originalFilenameWithWhitespace_trimsFilenameForTokenAndResult() {
    var multipartFile = multipartFile(" transactions.csv ");

    when(extractorRegistry.findByFormat("capital-one")).thenReturn(Optional.of(statementExtractor));
    when(statementExtractor.getFormatKey()).thenReturn("capital-one");
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(statementExtractor.extract(any(byte[].class), eq("checking"))).thenReturn(List.of());

    var result =
        transactionImportService.previewFile("capital-one", "checking", multipartFile, USER_ID);

    assertThat(result.sourceFile()).isEqualTo("transactions.csv");
    assertThat(result.previewImportToken()).isEqualTo("preview-token");
    verify(previewImportTokenService)
        .createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq("capital-one"),
            eq("checking"),
            any());
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
    return multipartFile("transactions.csv");
  }

  private static MockMultipartFile multipartFile(String originalFilename) {
    return new MockMultipartFile(
        "file",
        originalFilename,
        "text/csv",
        "Date,Description,Amount\n2024-01-15,Coffee Shop,4.50".getBytes());
  }

  private static TransactionDuplicateCandidate duplicateCandidate(
      String candidateKey, Long transactionId, String description) {
    return new TestTransactionDuplicateCandidate(candidateKey, transactionId, description);
  }

  private record TestTransactionDuplicateCandidate(
      String candidateKey, Long transactionId, String description)
      implements TransactionDuplicateCandidate {

    @Override
    public String getCandidateKey() {
      return candidateKey;
    }

    @Override
    public Long getTransactionId() {
      return transactionId;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
