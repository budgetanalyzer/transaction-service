package org.budgetanalyzer.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
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
import org.springframework.test.util.ReflectionTestUtils;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.domain.FileImport;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.domain.StatementFormat;
import org.budgetanalyzer.transaction.domain.TransactionDuplicateIdentity;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.repository.TransactionRepository;
import org.budgetanalyzer.transaction.repository.TransactionRepository.TransactionDuplicateCandidate;
import org.budgetanalyzer.transaction.service.dto.ParserAttempt;
import org.budgetanalyzer.transaction.service.dto.PreviewDuplicateReason;
import org.budgetanalyzer.transaction.service.dto.PreviewTransaction;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

@ExtendWith(MockitoExtension.class)
class TransactionImportServiceTest {

  private static final String USER_ID = "user-123";

  @Mock private StatementExtractorRegistry statementExtractorRegistry;

  @Mock private StatementFormatService statementFormatService;

  @Mock private TransactionRepository transactionRepository;

  @Mock private FileImportTrackingService fileImportTrackingService;

  @Mock private PreviewImportTokenService previewImportTokenService;

  @InjectMocks private TransactionImportService transactionImportService;

  @Test
  void shouldReturnOrderedResultsAndMarkLaterFileDuplicateWhenPreviewingTwoSources() {
    var statementFormat = statementFormat(42L);
    var firstParserRevision = parserRevision(statementFormat, 101L, "first-handler");
    var secondParserRevision = parserRevision(statementFormat, 102L, "second-handler");
    var firstTransaction = previewTransaction("Coffee Shop");
    var secondTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(firstTransaction);
    var firstFile = multipartFile("january.csv");
    var secondFile = multipartFile("february.csv");

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(
            new FileImportTrackingService.FileCheckResult("hash-january", Optional.empty()),
            new FileImportTrackingService.FileCheckResult("hash-february", Optional.empty()));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("january.csv"), eq("checking")))
        .thenReturn(List.of(ParserAttempt.matched(firstParserRevision, List.of(firstTransaction))));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("february.csv"), eq("checking")))
        .thenReturn(
            List.of(ParserAttempt.matched(secondParserRevision, List.of(secondTransaction))));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash-january"),
            eq("january.csv"),
            eq(42L),
            eq(101L),
            eq("checking"),
            any()))
        .thenReturn("token-january");
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash-february"),
            eq("february.csv"),
            eq(42L),
            eq(102L),
            eq("checking"),
            any()))
        .thenReturn("token-february");
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateKey), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionImportService.previewFiles(
            42L, "checking", List.of(firstFile, secondFile), USER_ID);

    assertThat(result.files())
        .extracting(
            previewFileResult -> previewFileResult.sourceFile(),
            previewFileResult -> previewFileResult.previewImportToken())
        .containsExactly(
            tuple("january.csv", "token-january"), tuple("february.csv", "token-february"));
    assertThat(result.files().getFirst().transactions().getFirst().duplicate()).isFalse();
    assertThat(result.files().get(1).transactions().getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.IN_BATCH);
    verify(statementFormatService, times(1)).getEnabledVisibleById(42L, USER_ID);
    verify(transactionRepository, times(1)).findDuplicateCandidates(Set.of(candidateKey), USER_ID);
  }

  @Test
  void shouldReturnNoResultAndPreserveCauseWhenSecondFileParserFails() {
    var statementFormat = statementFormat(42L);
    var firstParserRevision = parserRevision(statementFormat, 101L, "first-handler");
    var secondParserRevision = parserRevision(statementFormat, 102L, "second-handler");
    var firstFile = multipartFile("january.csv");
    var secondFile = multipartFile("february.csv");
    var parserCause = new IllegalArgumentException("invalid row");
    var parserFailure =
        new BusinessException(
            "Required column is missing.",
            BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
            parserCause);

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(
            new FileImportTrackingService.FileCheckResult("hash-january", Optional.empty()),
            new FileImportTrackingService.FileCheckResult("hash-february", Optional.empty()));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("january.csv"), eq("checking")))
        .thenReturn(
            List.of(
                ParserAttempt.matched(firstParserRevision, List.of(previewTransaction("Coffee")))));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("february.csv"), eq("checking")))
        .thenReturn(List.of(ParserAttempt.failed(secondParserRevision, parserFailure)));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash-january"),
            eq("january.csv"),
            eq(42L),
            eq(101L),
            eq("checking"),
            any()))
        .thenReturn("token-january");

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(firstFile, secondFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
              assertThat(businessException.getCause()).isSameAs(parserFailure);
              assertThat(businessException.getCause().getCause()).isSameAs(parserCause);
            });

    verifyNoInteractions(transactionRepository);
  }

  @Test
  void previewFilesParserFailureWithoutNestedCausePreservesParserException() {
    var statementFormat = statementFormat(42L);
    var parserRevision = parserRevision(statementFormat, 101L, "first-handler");
    var multipartFile = multipartFile("statement.csv");
    var parserFailure =
        new BusinessException(
            "Required column is missing.", BudgetAnalyzerError.CSV_PARSING_ERROR.name());

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("statement.csv"), eq("checking")))
        .thenReturn(List.of(ParserAttempt.failed(parserRevision, parserFailure)));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
              assertThat(businessException.getCause()).isSameAs(parserFailure);
              assertThat(parserFailure.getCause()).isNull();
            });

    verifyNoInteractions(transactionRepository, previewImportTokenService);
  }

  @Test
  void shouldStopBeforeReadingSecondFileWhenItsFilenameIsMissing() throws Exception {
    var firstFile = multipartFile();
    var secondFile = spy(multipartFile("   "));
    stubSuccessfulParse(List.of(previewTransaction("Coffee Shop")), firstFile, Optional.empty());

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(firstFile, secondFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verify(secondFile, never()).getBytes();
    verifyNoInteractions(transactionRepository);
  }

  @Test
  void shouldPreserveCauseAndSkipDownstreamWorkWhenFileReadFails() throws Exception {
    var multipartFile = spy(multipartFile("broken.csv"));
    var ioException = new IOException("storage failure");
    when(statementFormatService.getEnabledVisibleById(42L, USER_ID))
        .thenReturn(statementFormat(42L));
    doThrow(ioException).when(multipartFile).getBytes();

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.CSV_PARSING_ERROR.name());
              assertThat(businessException.getCause()).isSameAs(ioException);
            });

    verifyNoInteractions(
        fileImportTrackingService, statementExtractorRegistry, previewImportTokenService);
  }

  @Test
  void previewFile_statementFormatIdRecordsMatchedParserRevisionInToken() {
    var statementFormat = statementFormat(42L);
    var firstParserRevision = parserRevision(statementFormat, 101L, "first-handler");
    var secondParserRevision = parserRevision(statementFormat, 102L, "second-handler");
    var previewTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("transactions.csv"), eq("checking")))
        .thenReturn(
            List.of(
                ParserAttempt.notApplicable(firstParserRevision),
                ParserAttempt.matched(secondParserRevision, List.of(previewTransaction))));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq(42L),
            eq(102L),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);

    assertThat(result.files().getFirst().statementFormatId()).isEqualTo(42L);
    assertThat(result.files().getFirst().previewImportToken()).isEqualTo("preview-token");
    assertThat(result.files().getFirst().transactions()).hasSize(1);
    verify(previewImportTokenService)
        .createToken(
            eq(USER_ID),
            eq("hash"),
            eq("transactions.csv"),
            eq(42L),
            eq(102L),
            eq("checking"),
            any());
  }

  @Test
  void previewFile_statementFormatIdRejectsWhenNoParserRevisionMatches() {
    var statementFormat = statementFormat(42L);
    var parserRevision = parserRevision(statementFormat, 101L, "first-handler");
    var multipartFile = multipartFile();

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq("transactions.csv"), eq("checking")))
        .thenReturn(List.of(ParserAttempt.notApplicable(parserRevision)));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name());
            });

    verifyNoInteractions(previewImportTokenService);
  }

  @Test
  void previewFile_existingDatabaseDuplicate_marksTransactionWithExistingReason() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    stubSuccessfulParse(List.of(previewTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(List.of(duplicateCandidate(candidateKey, "Coffee Shop")));

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);

    assertThat(result.files().getFirst().fileImport().alreadyImported()).isFalse();
    assertThat(result.files().getFirst().previewImportToken()).isEqualTo("preview-token");
    assertThat(result.files().getFirst().fileImport().warningCode()).isNull();
    assertThat(result.files().getFirst().fileImport().previousImport()).isNull();
    assertThat(result.files().getFirst().transactions()).hasSize(1);
    assertThat(result.files().getFirst().transactions().getFirst().duplicate()).isTrue();
    assertThat(result.files().getFirst().transactions().getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.EXISTING_TRANSACTION);
    verify(transactionRepository).findDuplicateCandidates(Set.of(candidateCriteria), USER_ID);
  }

  @Test
  void shouldMarkExistingDuplicateWhenNormalizedDescriptionsMatch() {
    var previewTransaction = previewTransaction("X CORP. PAID FEATURESBASTROPTX");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    stubSuccessfulParse(List.of(previewTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(
            List.of(duplicateCandidate(candidateKey, "X CORP. PAID FEATURES BASTROP     TX")));

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);

    assertThat(result.files().getFirst().transactions()).hasSize(1);
    assertThat(result.files().getFirst().transactions().getFirst().duplicate()).isTrue();
    assertThat(result.files().getFirst().transactions().getFirst().duplicateReason())
        .isEqualTo(PreviewDuplicateReason.EXISTING_TRANSACTION);
  }

  @Test
  void shouldNotMarkExistingDuplicateWhenDescriptionIsMerelySimilar() {
    var previewTransaction = previewTransaction("PAYPAL DIGITAL SERVICES");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    stubSuccessfulParse(List.of(previewTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(List.of(duplicateCandidate(candidateKey, "PAYPAL DIGITAL SERVICE")));

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);

    assertThat(result.files().getFirst().transactions()).hasSize(1);
    assertThat(result.files().getFirst().transactions().getFirst().duplicate()).isFalse();
    assertThat(result.files().getFirst().transactions().getFirst().duplicateReason()).isNull();
  }

  @Test
  void shouldNotMarkEitherTransactionWhenSameFileContainsDuplicateRows() {
    var firstTransaction = previewTransaction("Coffee Shop");
    var secondTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(firstTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    stubSuccessfulParse(
        List.of(firstTransaction, secondTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);
    var transactions = result.files().getFirst().transactions();

    assertThat(transactions).hasSize(2);
    assertThat(transactions)
        .allSatisfy(
            transaction -> {
              assertThat(transaction.duplicate()).isFalse();
              assertThat(transaction.duplicateReason()).isNull();
            });
  }

  @Test
  void shouldNotMarkEitherTransactionWhenSameFileDescriptionsNormalizeEqually() {
    var firstTransaction = previewTransaction("X CORP. PAID FEATURES BASTROP     TX");
    var secondTransaction = previewTransaction("X CORP. PAID FEATURESBASTROPTX");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(firstTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    stubSuccessfulParse(
        List.of(firstTransaction, secondTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);
    var transactions = result.files().getFirst().transactions();

    assertThat(transactions).hasSize(2);
    assertThat(transactions)
        .allSatisfy(
            transaction -> {
              assertThat(transaction.duplicate()).isFalse();
              assertThat(transaction.duplicateReason()).isNull();
            });
  }

  @Test
  void shouldNotMarkInBatchDuplicateWhenPreviewDescriptionsAreMerelySimilar() {
    var firstTransaction = previewTransaction("PAYPAL DIGITAL SERVICES");
    var secondTransaction = previewTransaction("PAYPAL DIGITAL SERVICE");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(firstTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();

    stubSuccessfulParse(
        List.of(firstTransaction, secondTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenReturn(List.of());

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);
    var transactions = result.files().getFirst().transactions();

    assertThat(transactions).hasSize(2);
    assertThat(transactions)
        .allSatisfy(
            transaction -> {
              assertThat(transaction.duplicate()).isFalse();
              assertThat(transaction.duplicateReason()).isNull();
            });
  }

  @Test
  void previewFile_emptyExtraction_doesNotQueryDuplicateKeys() {
    var multipartFile = multipartFile();

    stubNoMatch(multipartFile);

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class);
    verify(transactionRepository, never()).findDuplicateCandidates(any(), any());
  }

  @Test
  void previewFile_duplicateLookupFailure_surfacesInfrastructureException() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var candidateKey = TransactionDuplicateMatcher.duplicateIdentity(previewTransaction);
    var candidateCriteria = candidateCriteria(candidateKey);
    var multipartFile = multipartFile();
    var dataAccessException = new DataAccessResourceFailureException("database unavailable");

    stubSuccessfulParse(List.of(previewTransaction), multipartFile, Optional.empty());
    when(transactionRepository.findDuplicateCandidates(Set.of(candidateCriteria), USER_ID))
        .thenThrow(dataAccessException);

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isSameAs(dataAccessException);
  }

  @Test
  void previewFile_existingFileImport_populatesFileImportWarning() {
    var previewTransaction = previewTransaction("Coffee Shop");
    var fileImport =
        FileImport.create("hash", "transactions.csv", 42L, 101L, "checking", 64L, 12, USER_ID);
    var multipartFile = multipartFile();

    stubSuccessfulParse(List.of(previewTransaction), multipartFile, Optional.of(fileImport));
    when(transactionRepository.findDuplicateCandidates(any(), eq(USER_ID))).thenReturn(List.of());

    var result =
        transactionImportService.previewFiles(42L, "checking", List.of(multipartFile), USER_ID);

    assertThat(result.files().getFirst().fileImport().alreadyImported()).isTrue();
    assertThat(result.files().getFirst().fileImport().warningCode().name())
        .isEqualTo("FILE_ALREADY_IMPORTED");
    assertThat(result.files().getFirst().fileImport().previousImport().originalFilename())
        .isEqualTo("transactions.csv");
    assertThat(result.files().getFirst().fileImport().previousImport().importedAt())
        .isEqualTo(fileImport.getImportedAt());
    assertThat(result.files().getFirst().fileImport().previousImport().statementFormatId())
        .isEqualTo(42L);
    assertThat(result.files().getFirst().fileImport().previousImport().accountId())
        .isEqualTo("checking");
    assertThat(result.files().getFirst().fileImport().previousImport().transactionCount())
        .isEqualTo(12);
  }

  @Test
  void previewFile_nullOriginalFilename_rejectsBeforeReadingFile() throws Exception {
    var multipartFile = spy(multipartFile(null));

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID))
        .thenReturn(statementFormat(42L));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verifyNoInteractions(fileImportTrackingService, previewImportTokenService);
    verify(multipartFile, never()).getBytes();
    verifyNoInteractions(statementExtractorRegistry);
  }

  @Test
  void previewFile_blankOriginalFilename_rejectsBeforeReadingFile() throws Exception {
    var multipartFile = spy(multipartFile("   "));

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID))
        .thenReturn(statementFormat(42L));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verifyNoInteractions(fileImportTrackingService, previewImportTokenService);
    verify(multipartFile, never()).getBytes();
    verifyNoInteractions(statementExtractorRegistry);
  }

  @Test
  void previewFile_emptyOriginalFilename_rejectsBeforeReadingFile() throws Exception {
    var multipartFile = spy(multipartFile(""));

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID))
        .thenReturn(statementFormat(42L));

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception -> {
              var businessException = (BusinessException) exception;
              assertThat(businessException.getCode())
                  .isEqualTo(BudgetAnalyzerError.MISSING_ORIGINAL_FILENAME.name());
            });

    verifyNoInteractions(fileImportTrackingService, previewImportTokenService);
    verify(multipartFile, never()).getBytes();
    verifyNoInteractions(statementExtractorRegistry);
  }

  @Test
  void previewFile_originalFilenameWithWhitespace_trimsFilenameForTokenAndResult() {
    var multipartFile = multipartFile(" transactions.csv ");

    stubNoMatch(multipartFile);

    assertThatThrownBy(
            () ->
                transactionImportService.previewFiles(
                    42L, "checking", List.of(multipartFile), USER_ID))
        .isInstanceOf(BusinessException.class);
    verify(statementExtractorRegistry)
        .attemptParse(
            any(StatementFormat.class), any(byte[].class), eq("transactions.csv"), eq("checking"));
  }

  private void stubSuccessfulParse(
      List<PreviewTransaction> previewTransactions,
      MockMultipartFile multipartFile,
      Optional<FileImport> existingImport) {
    var statementFormat = statementFormat(42L);
    var parserRevision = parserRevision(statementFormat, 101L, "test-handler");
    var originalFilename = multipartFile.getOriginalFilename().trim();

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", existingImport));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq(originalFilename), eq("checking")))
        .thenReturn(List.of(ParserAttempt.matched(parserRevision, previewTransactions)));
    when(previewImportTokenService.createToken(
            eq(USER_ID),
            eq("hash"),
            eq(originalFilename),
            eq(42L),
            eq(101L),
            eq("checking"),
            any()))
        .thenReturn("preview-token");
  }

  private void stubNoMatch(MockMultipartFile multipartFile) {
    var statementFormat = statementFormat(42L);
    var parserRevision = parserRevision(statementFormat, 101L, "test-handler");
    var originalFilename = multipartFile.getOriginalFilename().trim();

    when(statementFormatService.getEnabledVisibleById(42L, USER_ID)).thenReturn(statementFormat);
    when(fileImportTrackingService.checkFile(any(byte[].class), eq(USER_ID)))
        .thenReturn(new FileImportTrackingService.FileCheckResult("hash", Optional.empty()));
    when(statementExtractorRegistry.attemptParse(
            eq(statementFormat), any(byte[].class), eq(originalFilename), eq("checking")))
        .thenReturn(List.of(ParserAttempt.notApplicable(parserRevision)));
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

  private static StatementFormat statementFormat(Long id) {
    var statementFormat =
        StatementFormat.createSystemPdfFormat("Test Bank - Statement", "Test Bank", "USD");
    ReflectionTestUtils.setField(statementFormat, "id", id);
    return statementFormat;
  }

  private static ParserRevision parserRevision(
      StatementFormat statementFormat, Long id, String handlerKey) {
    var parserRevision = ParserRevision.createStaticHandler(statementFormat, 1, handlerKey);
    ReflectionTestUtils.setField(parserRevision, "id", id);
    return parserRevision;
  }

  private static TransactionDuplicateCandidate duplicateCandidate(
      TransactionDuplicateIdentity candidateKey, String description) {
    return new TestTransactionDuplicateCandidate(candidateCriteria(candidateKey), description);
  }

  private static TransactionDuplicateIdentity candidateCriteria(
      TransactionDuplicateIdentity candidateKey) {
    return candidateKey;
  }

  private record TestTransactionDuplicateCandidate(
      TransactionDuplicateIdentity duplicateIdentity, String description)
      implements TransactionDuplicateCandidate {

    @Override
    public TransactionDuplicateIdentity getDuplicateIdentity() {
      return duplicateIdentity;
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}
