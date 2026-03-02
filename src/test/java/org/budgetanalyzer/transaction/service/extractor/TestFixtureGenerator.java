package org.budgetanalyzer.transaction.service.extractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * Generates anonymized PDF test fixtures for Capital One statement extractors.
 *
 * <p>Creates synthetic PDFs using PDFBox that produce text matching extractor regex patterns. Safer
 * than qpdf text-replacement on real PDFs: no risk of residual PII in binary blobs.
 *
 * <p>Run once: {@code ./gradlew test --tests "*TestFixtureGenerator*"}
 */
class TestFixtureGenerator {

  private static final Path FIXTURES_DIR = Path.of("src/test/resources/fixtures");
  private static final float FONT_SIZE = 10f;
  private static final float LEADING = 14f;
  private static final float LEFT_MARGIN = 50f;
  private static final float TOP_START = 750f;

  @Test
  void generateAllFixtures() throws Exception {
    Files.createDirectories(FIXTURES_DIR);
    generateCreditMonthlyFixture();
    generateBankMonthlyFixture();
    generateCreditYearlySummaryFixture();
  }

  private void generateCreditMonthlyFixture() throws IOException {
    // Text must trigger canHandle: "Credit Card" + "\d+ days in Billing Cycle" + "capital one"
    // Text must contain statement period, section headers, and transaction lines
    String[] lines = {
      "Capital One",
      "",
      "Credit Card Statement",
      "",
      "Account Summary",
      "New Balance $1,234.56",
      "Statement Period: Nov 19, 2025 - Dec 19, 2025",
      "31 days in Billing Cycle",
      "",
      "Payments, Credits and Adjustments",
      "Trans Date Post Date Description Amount",
      "Nov 20 Nov 21 ONLINE PAYMENT THANK YOU $500.00",
      "Dec 1 Dec 2 AUTOPAY PAYMENT THANK YOU $300.00",
      "",
      "Transactions",
      "Trans Date Post Date Description Amount",
      "Nov 21 Nov 22 CORNER GROCERY ANYTOWN CA $45.67",
      "Nov 22 Nov 23 MERCHANDISE RETURN - $124.99",
      "Nov 24 Nov 25 ACME SOFTWARE SUBSCRIPTION $166.74",
      "Nov 25 Nov 26 GAS STATION SPRINGFIELD VA $52.30",
      "Nov 26 Nov 27 FURNITURE STORE ONLINE $2,400.32",
      "Nov 28 Nov 29 TECH COMPANY $5.00",
      "Dec 3 Dec 4 RESTAURANT DOWNTOWN $78.50",
      "Dec 5 Dec 6 UTILITY COMPANY $120.00",
      "Dec 8 Dec 9 STREAMING SERVICE $15.99",
      "Dec 10 Dec 11 PHARMACY ANYTOWN CA $32.15",
      "Dec 15 Dec 16 ONLINE MARKETPLACE $89.99",
    };

    writePdf(FIXTURES_DIR.resolve("cap-one-credit-monthly-sample.pdf"), lines);
  }

  private void generateBankMonthlyFixture() throws IOException {
    // Text must trigger canHandle: "Capital One 360" + "bank statement" (DOTALL)
    // Text must contain statement period, account headers, transaction lines with Category column
    String[] lines = {
      "Capital One 360",
      "Your bank statement",
      "",
      "Nov 1 - Nov 30, 2025",
      "",
      "360 Checking - 36012345678",
      "DATE DESCRIPTION CATEGORY AMOUNT BALANCE",
      "Opening Balance $5,000.00",
      "Nov 3 ELECTRIC COMPANY ONLINE PMT Debit - $85.50 $4,914.50",
      "Nov 13 ONLINE BILL PAY Debit - $1,862.72 $3,051.78",
      "Nov 18 ATM WITHDRAWAL ANYTOWN CA Debit - $200.00 $2,851.78",
      "Nov 20 ATM WITHDRAWAL SPRINGFIELD VA Debit - $100.00 $2,751.78",
      "Nov 24 EMPLOYER DIRECT DEPOSIT Credit + $5,000.00 $7,751.78",
      "Nov 30 Monthly Interest Paid Credit + $0.14 $7,751.92",
      "Closing Balance $7,751.92",
    };

    writePdf(FIXTURES_DIR.resolve("cap-one-bank-monthly-sample.pdf"), lines);
  }

  private void generateCreditYearlySummaryFixture() throws IOException {
    // Text must trigger canHandle: "Year-End Summary \d{4}" + "capital one"
    // Text must contain "Section 4" / "Transaction Details", category headers, transaction lines
    String[] lines = {
      "Capital One",
      "Year-End Summary 2024",
      "",
      "Section 1 - Account Overview",
      "Card Ending in 1234",
      "",
      "Section 4",
      "Transaction Details",
      "",
      "Dining",
      "Date Merchant Name Merchant Location Amount",
      "04/12 TAQUERIA DEL SOL ANYTOWN CA $55.12",
      "06/15 PIZZA PALACE SPRINGFIELD VA $32.50",
      "08/20 SUSHI RESTAURANT $18.75",
      "",
      "Gas/Automotive",
      "Date Merchant Name Merchant Location Amount",
      "01/05 GAS STATION ANYTOWN CA $45.00",
      "03/18 AUTO PARTS STORE $89.99",
      "",
      "Merchandise",
      "Date Merchant Name Merchant Location Amount",
      "02/14 DEPARTMENT STORE $125.00",
      "05/22 ONLINE SHOP $37.27",
      "07/10 ELECTRONICS STORE $299.99",
      "09/30 BOOKSTORE ONLINE $22.50",
      "",
      "Entertainment",
      "Date Merchant Name Merchant Location Amount",
      "04/01 MOVIE THEATER $15.00",
      "11/15 CONCERT TICKETS $75.00",
      "",
      "Travel/Airfare",
      "Date Merchant Name Merchant Location Amount",
      "03/01 AIRLINES BOOKING $450.00",
      "",
      "Services/Healthcare",
      "Date Merchant Name Merchant Location Amount",
      "06/01 PHARMACY $12.50",
      "",
      "Other",
      "Date Merchant Name Merchant Location Amount",
      "10/15 MISC PURCHASE $45.00",
      "08/05 REFUND FROM ONLINE SHOP -$37.27",
    };

    writePdf(FIXTURES_DIR.resolve("cap-one-credit-yearly-summary-sample.pdf"), lines);
  }

  private void writePdf(Path outputPath, String[] textLines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        contentStream.beginText();
        contentStream.setFont(font, FONT_SIZE);
        contentStream.setLeading(LEADING);
        contentStream.newLineAtOffset(LEFT_MARGIN, TOP_START);

        for (String line : textLines) {
          if (line.isEmpty()) {
            contentStream.newLine();
          } else {
            contentStream.showText(line);
            contentStream.newLine();
          }
        }

        contentStream.endText();
      }

      document.save(outputPath.toFile());
    }
  }
}
