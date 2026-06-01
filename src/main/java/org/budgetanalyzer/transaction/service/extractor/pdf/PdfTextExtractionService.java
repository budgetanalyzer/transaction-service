package org.budgetanalyzer.transaction.service.extractor.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

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
  private static final float TABLE_VERTICAL_GAP_THRESHOLD = 32.0F;
  private static final Pattern LEADING_DATE_CELL_PATTERN =
      Pattern.compile(
          "^\\s*(\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|[A-Za-z]{3,9}\\s+\\d{1,2}"
              + "(?:,?\\s+\\d{4})?)\\s+(.+)$");

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
      var headerPrefixLines = new ArrayList<PdfTextLine>();
      for (var pdfTextLine : pdfTextPage.lines()) {
        if (pdfTextLine.cells().size() >= 2) {
          if (!currentBlock.isEmpty()
              && Math.abs(pdfTextLine.y() - currentBlock.getLast().y())
                  > TABLE_VERTICAL_GAP_THRESHOLD) {
            addTableCandidateIfPresent(pdfTextPage.pageNumber(), currentBlock, tableCandidates);
            currentBlock.clear();
          }
          if (currentBlock.isEmpty() && isHeaderPrefixForLine(headerPrefixLines, pdfTextLine)) {
            currentBlock.addAll(headerPrefixLines);
          }
          currentBlock.add(pdfTextLine);
          headerPrefixLines.clear();
        } else {
          addTableCandidateIfPresent(pdfTextPage.pageNumber(), currentBlock, tableCandidates);
          currentBlock.clear();
          updateHeaderPrefixLines(headerPrefixLines, pdfTextLine);
        }
      }
      addTableCandidateIfPresent(pdfTextPage.pageNumber(), currentBlock, tableCandidates);
    }
    return List.copyOf(tableCandidates);
  }

  private void updateHeaderPrefixLines(
      List<PdfTextLine> headerPrefixLines, PdfTextLine pdfTextLine) {
    if (pdfTextLine.cells().size() != 1 || !isDateHeaderPrefix(pdfTextLine)) {
      headerPrefixLines.clear();
      return;
    }
    if (!headerPrefixLines.isEmpty()
        && Math.abs(pdfTextLine.y() - headerPrefixLines.getLast().y())
            > TABLE_VERTICAL_GAP_THRESHOLD) {
      headerPrefixLines.clear();
    }
    headerPrefixLines.add(pdfTextLine);
  }

  private boolean isHeaderPrefixForLine(
      List<PdfTextLine> headerPrefixLines, PdfTextLine headerLine) {
    if (headerPrefixLines.isEmpty()) {
      return false;
    }
    if (Math.abs(headerLine.y() - headerPrefixLines.getLast().y()) > TABLE_VERTICAL_GAP_THRESHOLD) {
      return false;
    }
    return isDateHeaderText(joinHeaderPrefixText(headerPrefixLines));
  }

  private boolean isDateHeaderPrefix(PdfTextLine pdfTextLine) {
    return isDateHeaderText(pdfTextLine.cells().getFirst().text());
  }

  private boolean isDateHeaderText(String text) {
    var normalizedText = normalizedCell(text);
    return normalizedText.equals("date")
        || normalizedText.equals("dateof")
        || normalizedText.equals("transaction")
        || normalizedText.equals("transactiondate")
        || normalizedText.equals("dateoftransaction");
  }

  private void addTableCandidateIfPresent(
      int pageNumber, List<PdfTextLine> block, List<PdfTextTableCandidate> tableCandidates) {
    if (block.size() < MIN_TABLE_LINES) {
      return;
    }

    var headerTextCells = headerCells(block);
    var headerLineCount = headerLineCount(block);
    var headerCells = headerTextCells.stream().map(PdfTextCell::text).toList();
    var dataRows = new ArrayList<List<String>>();
    var repeatedHeaderCount = 0;
    for (var pdfTextLine : block.stream().skip(headerLineCount).toList()) {
      var row = alignRowToHeader(pdfTextLine.cells(), headerTextCells);
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
            List.copyOf(dataRows),
            sampleRows,
            dataRows.size(),
            repeatedHeaderCount));
  }

  private List<PdfTextCell> headerCells(List<PdfTextLine> block) {
    var headerPrefixLineCount = headerPrefixLineCount(block);
    if (headerPrefixLineCount > 0) {
      return mergeSplitHeaderCells(
          block.subList(0, headerPrefixLineCount), block.get(headerPrefixLineCount).cells());
    }
    return block.getFirst().cells();
  }

  private int headerLineCount(List<PdfTextLine> block) {
    return headerPrefixLineCount(block) + 1;
  }

  private int headerPrefixLineCount(List<PdfTextLine> block) {
    var headerPrefixLineCount = 0;
    for (var pdfTextLine : block) {
      if (pdfTextLine.cells().size() != 1 || !isDateHeaderPrefix(pdfTextLine)) {
        break;
      }
      headerPrefixLineCount++;
    }
    if (block.size() < headerPrefixLineCount + 2) {
      return 0;
    }
    return headerPrefixLineCount;
  }

  private List<PdfTextCell> mergeSplitHeaderCells(
      List<PdfTextLine> headerPrefixLines, List<PdfTextCell> headerCells) {
    var mergedHeaderCells = new ArrayList<PdfTextCell>();
    var headerPrefixText = joinHeaderPrefixText(headerPrefixLines);
    var headerPrefixCell = headerPrefixLines.getLast().cells().getFirst();
    if (isCompleteDateHeaderPrefix(headerPrefixText)) {
      mergedHeaderCells.add(
          new PdfTextCell(
              headerPrefixCell.pageNumber(),
              headerPrefixCell.lineNumber(),
              1,
              headerPrefixText,
              headerPrefixCell.startX(),
              headerPrefixCell.endX()));
      mergedHeaderCells.addAll(headerCells);
      return List.copyOf(mergedHeaderCells);
    }
    var nearestCell = nearestCell(headerCells, headerPrefixCell);
    for (var headerCell : headerCells) {
      var text = headerCell.text();
      if (headerCell.equals(nearestCell)) {
        text = headerPrefixText + " " + headerCell.text();
      }
      mergedHeaderCells.add(
          new PdfTextCell(
              headerCell.pageNumber(),
              headerCell.lineNumber(),
              headerCell.columnIndex(),
              text,
              headerCell.startX(),
              headerCell.endX()));
    }
    return List.copyOf(mergedHeaderCells);
  }

  private String joinHeaderPrefixText(List<PdfTextLine> headerPrefixLines) {
    return headerPrefixLines.stream()
        .map(pdfTextLine -> pdfTextLine.cells().getFirst().text())
        .reduce((first, second) -> first + " " + second)
        .orElse("");
  }

  private boolean isCompleteDateHeaderPrefix(String headerPrefixText) {
    var normalizedHeaderPrefixText = normalizedCell(headerPrefixText);
    return normalizedHeaderPrefixText.equals("date")
        || normalizedHeaderPrefixText.equals("transactiondate")
        || normalizedHeaderPrefixText.equals("dateoftransaction");
  }

  private List<String> alignRowToHeader(List<PdfTextCell> rowCells, List<PdfTextCell> headerCells) {
    if (rowCells.size() == headerCells.size()) {
      return rowCells.stream().map(PdfTextCell::text).toList();
    }
    if (rowCells.size() + 1 == headerCells.size()
        && hasLeadingDateDescriptionHeaders(headerCells)) {
      var splitLeadingDateCell = splitLeadingDateCell(rowCells.getFirst());
      if (splitLeadingDateCell != null) {
        var alignedRow = new ArrayList<String>();
        alignedRow.add(splitLeadingDateCell.date());
        alignedRow.add(splitLeadingDateCell.description());
        rowCells.stream().skip(1).map(PdfTextCell::text).forEach(alignedRow::add);
        return List.copyOf(alignedRow);
      }
    }
    var alignedRow = new ArrayList<String>();
    for (var headerCell : headerCells) {
      var nearestCell = nearestCell(rowCells, headerCell);
      alignedRow.add(nearestCell == null ? "" : nearestCell.text());
    }
    return List.copyOf(alignedRow);
  }

  private boolean hasLeadingDateDescriptionHeaders(List<PdfTextCell> headerCells) {
    if (headerCells.size() < 3) {
      return false;
    }
    var firstHeader = normalizedCell(headerCells.get(0).text());
    var secondHeader = normalizedCell(headerCells.get(1).text());
    return firstHeader.contains("date")
        && (secondHeader.contains("description")
            || secondHeader.contains("merchant")
            || secondHeader.contains("memo")
            || secondHeader.contains("details"));
  }

  private SplitLeadingDateCell splitLeadingDateCell(PdfTextCell pdfTextCell) {
    var matcher = LEADING_DATE_CELL_PATTERN.matcher(pdfTextCell.text());
    if (!matcher.matches()) {
      return null;
    }
    return new SplitLeadingDateCell(matcher.group(1).strip(), matcher.group(2).strip());
  }

  private PdfTextCell nearestCell(List<PdfTextCell> rowCells, PdfTextCell headerCell) {
    var headerCenterX = centerX(headerCell);
    var bestCell = (PdfTextCell) null;
    var bestDistance = Float.MAX_VALUE;
    for (var rowCell : rowCells) {
      var distance = Math.abs(centerX(rowCell) - headerCenterX);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestCell = rowCell;
      }
    }
    if (bestCell == null || bestDistance > CELL_GAP_THRESHOLD * 4) {
      return null;
    }
    return bestCell;
  }

  private float centerX(PdfTextCell pdfTextCell) {
    return (pdfTextCell.startX() + pdfTextCell.endX()) / 2.0F;
  }

  private List<String> normalizedCells(List<String> cells) {
    return cells.stream().map(this::normalizedCell).toList();
  }

  private String normalizedCell(String cell) {
    return cell.toLowerCase().replaceAll("[^a-z0-9]", "");
  }

  private BusinessException pdfParsingError(String message) {
    return new BusinessException(message, BudgetAnalyzerError.PDF_PARSING_ERROR.name());
  }

  private record TextChunk(int pageNumber, float startX, float endX, float y, String text) {}

  private record SplitLeadingDateCell(String date, String description) {}

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
