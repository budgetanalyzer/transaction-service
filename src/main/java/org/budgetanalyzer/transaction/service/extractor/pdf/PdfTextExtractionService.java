package org.budgetanalyzer.transaction.service.extractor.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import org.budgetanalyzer.service.exception.BusinessException;
import org.budgetanalyzer.transaction.service.BudgetAnalyzerError;

/** Extracts and normalizes text from text-based PDF statement samples. */
@Service
public class PdfTextExtractionService {

  private static final int MIN_EXTRACTED_CHARACTERS = 20;
  private static final int MIN_TABLE_LINES = 2;
  private static final int SAMPLE_ROW_LIMIT = 5;
  private static final float LINE_Y_TOLERANCE = 2.0F;
  private static final float CELL_GAP_THRESHOLD = 8.0F;
  private static final float TABLE_VERTICAL_GAP_THRESHOLD = 24.0F;

  /**
   * Extracts normalized pages, lines, cells, and coarse table candidates from a text PDF.
   *
   * @param fileContent uploaded PDF bytes
   * @param filename uploaded filename
   * @return normalized extracted PDF text
   */
  public PdfTextDocument extract(byte[] fileContent, String filename) {
    if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
      throw pdfParsingError("PDF text extraction requires a .pdf file.");
    }

    try (PDDocument document = Loader.loadPDF(fileContent)) {
      var positionAwareTextStripper = new PositionAwareTextStripper();
      positionAwareTextStripper.setSortByPosition(true);
      positionAwareTextStripper.getText(document);

      var pages =
          buildPages(document.getNumberOfPages(), positionAwareTextStripper.getTextChunks());
      if (extractedCharacterCount(pages) < MIN_EXTRACTED_CHARACTERS) {
        throw pdfParsingError(
            "PDF does not contain enough extractable text. Scanned or OCR-dependent PDFs are not "
                + "supported.");
      }

      return new PdfTextDocument(pages, detectTableCandidates(pages));
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (IOException ioException) {
      throw new BusinessException(
          "Failed to extract text from PDF: " + ioException.getMessage(),
          BudgetAnalyzerError.PDF_PARSING_ERROR.name(),
          ioException);
    }
  }

  private List<PdfTextPage> buildPages(int pageCount, List<TextChunk> textChunks) {
    var textChunksByPage = new HashMap<Integer, List<TextChunk>>();
    for (var textChunk : textChunks) {
      textChunksByPage
          .computeIfAbsent(textChunk.pageNumber(), key -> new ArrayList<>())
          .add(textChunk);
    }

    var pages = new ArrayList<PdfTextPage>();
    for (var pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
      pages.add(
          new PdfTextPage(pageNumber, buildLines(pageNumber, textChunksByPage.get(pageNumber))));
    }
    return List.copyOf(pages);
  }

  private List<PdfTextLine> buildLines(int pageNumber, List<TextChunk> textChunks) {
    if (textChunks == null || textChunks.isEmpty()) {
      return List.of();
    }

    var sortedTextChunks =
        textChunks.stream()
            .sorted(Comparator.comparing(TextChunk::y).thenComparing(TextChunk::startX))
            .toList();
    var lineBuckets = new ArrayList<List<TextChunk>>();
    for (var textChunk : sortedTextChunks) {
      var lineBucket = findLineBucket(lineBuckets, textChunk);
      if (lineBucket == null) {
        lineBucket = new ArrayList<>();
        lineBuckets.add(lineBucket);
      }
      lineBucket.add(textChunk);
    }

    var lines = new ArrayList<PdfTextLine>();
    for (var lineIndex = 0; lineIndex < lineBuckets.size(); lineIndex++) {
      var lineChunks =
          lineBuckets.get(lineIndex).stream()
              .sorted(Comparator.comparing(TextChunk::startX))
              .toList();
      lines.add(
          new PdfTextLine(
              pageNumber,
              lineIndex + 1,
              lineChunks.getFirst().y(),
              buildCells(pageNumber, lineIndex + 1, lineChunks)));
    }
    return List.copyOf(lines);
  }

  private List<TextChunk> findLineBucket(List<List<TextChunk>> lineBuckets, TextChunk textChunk) {
    for (var lineBucket : lineBuckets) {
      if (Math.abs(lineBucket.getFirst().y() - textChunk.y()) <= LINE_Y_TOLERANCE) {
        return lineBucket;
      }
    }
    return null;
  }

  private List<PdfTextCell> buildCells(int pageNumber, int lineNumber, List<TextChunk> lineChunks) {
    if (lineChunks.size() == 1) {
      return splitSingleChunkCell(pageNumber, lineNumber, lineChunks.getFirst());
    }

    var cells = new ArrayList<PdfTextCell>();
    var cellText = new StringBuilder();
    var cellStartX = lineChunks.getFirst().startX();
    var cellEndX = lineChunks.getFirst().endX();
    for (var textChunk : lineChunks) {
      if (!cellText.isEmpty() && textChunk.startX() - cellEndX > CELL_GAP_THRESHOLD) {
        cells.add(
            new PdfTextCell(
                pageNumber,
                lineNumber,
                cells.size() + 1,
                cellText.toString().strip(),
                cellStartX,
                cellEndX));
        cellText = new StringBuilder();
        cellStartX = textChunk.startX();
      }
      if (!cellText.isEmpty()) {
        cellText.append(' ');
      }
      cellText.append(textChunk.text());
      cellEndX = textChunk.endX();
    }
    if (!cellText.isEmpty()) {
      cells.add(
          new PdfTextCell(
              pageNumber,
              lineNumber,
              cells.size() + 1,
              cellText.toString().strip(),
              cellStartX,
              cellEndX));
    }
    return List.copyOf(cells);
  }

  private List<PdfTextCell> splitSingleChunkCell(
      int pageNumber, int lineNumber, TextChunk textChunk) {
    var parts = textChunk.text().strip().split("\\s{2,}");
    if (parts.length <= 1) {
      return List.of(
          new PdfTextCell(
              pageNumber,
              lineNumber,
              1,
              textChunk.text().strip(),
              textChunk.startX(),
              textChunk.endX()));
    }

    var cells = new ArrayList<PdfTextCell>();
    for (var index = 0; index < parts.length; index++) {
      cells.add(
          new PdfTextCell(
              pageNumber,
              lineNumber,
              index + 1,
              parts[index].strip(),
              textChunk.startX(),
              textChunk.endX()));
    }
    return List.copyOf(cells);
  }

  private int extractedCharacterCount(List<PdfTextPage> pages) {
    return pages.stream()
        .flatMap(pdfTextPage -> pdfTextPage.lines().stream())
        .map(PdfTextLine::text)
        .mapToInt(String::length)
        .sum();
  }

  private List<PdfTextTableCandidate> detectTableCandidates(List<PdfTextPage> pages) {
    var tableCandidates = new ArrayList<PdfTextTableCandidate>();
    for (var pdfTextPage : pages) {
      var currentBlock = new ArrayList<PdfTextLine>();
      for (var pdfTextLine : pdfTextPage.lines()) {
        if (pdfTextLine.cells().size() >= 2) {
          if (!currentBlock.isEmpty()
              && Math.abs(pdfTextLine.y() - currentBlock.getLast().y())
                  > TABLE_VERTICAL_GAP_THRESHOLD) {
            addTableCandidateIfPresent(pdfTextPage.pageNumber(), currentBlock, tableCandidates);
            currentBlock.clear();
          }
          currentBlock.add(pdfTextLine);
        } else {
          addTableCandidateIfPresent(pdfTextPage.pageNumber(), currentBlock, tableCandidates);
          currentBlock.clear();
        }
      }
      addTableCandidateIfPresent(pdfTextPage.pageNumber(), currentBlock, tableCandidates);
    }
    return List.copyOf(tableCandidates);
  }

  private void addTableCandidateIfPresent(
      int pageNumber, List<PdfTextLine> block, List<PdfTextTableCandidate> tableCandidates) {
    if (block.size() < MIN_TABLE_LINES) {
      return;
    }

    var headerCells = block.getFirst().cells().stream().map(PdfTextCell::text).toList();
    var dataRows = new ArrayList<List<String>>();
    var repeatedHeaderCount = 0;
    for (var pdfTextLine : block.stream().skip(1).toList()) {
      var row = pdfTextLine.cells().stream().map(PdfTextCell::text).toList();
      if (normalizedCells(row).equals(normalizedCells(headerCells))) {
        repeatedHeaderCount++;
      } else {
        dataRows.add(row);
      }
    }
    var sampleRows = dataRows.stream().limit(SAMPLE_ROW_LIMIT).toList();
    tableCandidates.add(
        new PdfTextTableCandidate(
            pageNumber,
            block.getFirst().lineNumber(),
            block.getLast().lineNumber(),
            headerCells,
            sampleRows,
            dataRows.size(),
            repeatedHeaderCount));
  }

  private List<String> normalizedCells(List<String> cells) {
    return cells.stream().map(cell -> cell.toLowerCase().replaceAll("[^a-z0-9]", "")).toList();
  }

  private BusinessException pdfParsingError(String message) {
    return new BusinessException(message, BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  private record TextChunk(int pageNumber, float startX, float endX, float y, String text) {}

  private static class PositionAwareTextStripper extends PDFTextStripper {

    private final List<TextChunk> textChunks = new ArrayList<>();
    private int currentPage;

    PositionAwareTextStripper() throws IOException {}

    List<TextChunk> getTextChunks() {
      return List.copyOf(textChunks);
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
      currentPage++;
      super.startPage(page);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      var normalizedText = text.strip();
      if (normalizedText.isBlank() || textPositions.isEmpty()) {
        return;
      }

      var firstTextPosition = textPositions.getFirst();
      var lastTextPosition = textPositions.getLast();
      textChunks.add(
          new TextChunk(
              currentPage,
              firstTextPosition.getXDirAdj(),
              lastTextPosition.getXDirAdj() + lastTextPosition.getWidthDirAdj(),
              firstTextPosition.getYDirAdj(),
              normalizedText));
    }
  }
}
