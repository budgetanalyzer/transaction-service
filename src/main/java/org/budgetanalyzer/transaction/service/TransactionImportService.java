package org.budgetanalyzer.transaction.service;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.api.response.PreviewResponse;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractor;
import org.budgetanalyzer.transaction.service.extractor.StatementExtractorRegistry;

/**
 * Service for importing transactions from statement files (CSV, PDF, etc.).
 *
 * <p>Uses the StatementExtractorRegistry to find the appropriate extractor based on the format key.
 * All file types are handled through the unified StatementExtractor interface.
 */
@Service
public class TransactionImportService {

  private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);

  private final StatementExtractorRegistry extractorRegistry;

  /**
   * Constructs a new TransactionImportService.
   *
   * @param extractorRegistry the registry for looking up statement extractors
   */
  public TransactionImportService(StatementExtractorRegistry extractorRegistry) {
    this.extractorRegistry = extractorRegistry;
  }

  /**
   * Previews transactions from any supported file type (PDF or CSV).
   *
   * <p>The format parameter is required and determines which extractor to use. The extractor is
   * looked up from the StatementExtractorRegistry, which manages both static (PDF) and dynamic
   * (CSV) extractors.
   *
   * @param format the format key (e.g., "capital-one-yearly" for PDF, "capital-one" for CSV)
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param file the file to preview (PDF or CSV)
   * @return PreviewResponse containing extracted transactions
   * @throws BusinessException if the format is not supported or parsing fails
   */
  public PreviewResponse previewFile(String format, String accountId, MultipartFile file) {
    var extractor =
        extractorRegistry
            .findByFormat(format)
            .orElseThrow(
                () ->
                    new BusinessException(
                        "Format not supported: " + format,
                        BudgetAnalyzerError.FORMAT_NOT_SUPPORTED.name()));

    return previewWithExtractor(extractor, accountId, file);
  }

  /**
   * Previews transactions using a specific statement extractor.
   *
   * @param extractor the extractor to use
   * @param accountId optional account identifier to pre-fill for all transactions
   * @param file the file to preview
   * @return PreviewResponse containing extracted transactions
   * @throws BusinessException if parsing fails
   */
  private PreviewResponse previewWithExtractor(
      StatementExtractor extractor, String accountId, MultipartFile file) {
    try {
      if (file.isEmpty()) {
        throw new BusinessException("File is empty", BudgetAnalyzerError.CSV_PARSING_ERROR.name());
      }

      log.info(
          "Previewing file with extractor '{}': {}",
          extractor.getFormatKey(),
          file.getOriginalFilename());

      var fileContent = file.getBytes();
      var extractionResult = extractor.extract(fileContent, accountId);

      log.info(
          "Successfully previewed {} transactions from file {}",
          extractionResult.transactions().size(),
          file.getOriginalFilename());

      return new PreviewResponse(
          file.getOriginalFilename(),
          extractor.getFormatKey(),
          extractionResult.transactions(),
          extractionResult.warnings());
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (IOException e) {
      throw new BusinessException(
          "Failed to read file: " + e.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          e);
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to preview file: " + e.getMessage(),
          BudgetAnalyzerError.CSV_PARSING_ERROR.name(),
          e);
    }
  }
}
