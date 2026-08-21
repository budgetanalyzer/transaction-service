package org.budgetanalyzer.transaction.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

class StatementFormatControllerAuthorizationIntegrationTest
    extends ControllerIntegrationTestSupport {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 150F;
  private static final float AMOUNT_X = 400F;

  @Test
  void returns401WithoutAuthentication() throws Exception {
    mockMvc.perform(get("/v1/statement-formats")).andExpect(status().isUnauthorized());
  }

  @Test
  void returns200ForListWithReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForListWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForGetWithReadPermission() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);

    mockMvc
        .perform(
            get("/v1/statement-formats/{id}", parserRevision.getStatementFormat().getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForGetWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/statement-formats/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns201ForCreateWithWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createValidFormatJson()))
        .andExpect(status().isCreated());
  }

  @Test
  void returns403ForCreateWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createValidFormatJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForUpdateWithWritePermission() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);

    mockMvc
        .perform(
            put("/v1/statement-formats/{id}", parserRevision.getStatementFormat().getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"Updated Bank\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForUpdateWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            put("/v1/statement-formats/1")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\": \"Updated Bank\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns204ForHideWithWritePermission() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);

    mockMvc
        .perform(
            post("/v1/statement-formats/{id}/hide", parserRevision.getStatementFormat().getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("statementformats:write")))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns403ForHideWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats/1/hide")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns204ForUnhideWithWritePermission() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);

    mockMvc
        .perform(
            post("/v1/statement-formats/{id}/unhide", parserRevision.getStatementFormat().getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("statementformats:write")))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns403ForUnhideWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats/1/unhide")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForCsvWizardAnalyzeWithWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/analyze")
                .file(csvFile())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("statementformats:write")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForCsvWizardAnalyzeWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/analyze")
                .file(csvFile())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForPdfWizardAnalyzeWithWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/analyze")
                .file(pdfFile())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("statementformats:write")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForPdfWizardAnalyzeWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/analyze")
                .file(pdfFile())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForPdfWizardPreviewWithWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/preview")
                .file(pdfFile())
                .file(jsonPart("request", pdfPreviewRequestJson()))
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("statementformats:write")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForPdfWizardPreviewWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/preview")
                .file(pdfFile())
                .file(jsonPart("request", pdfPreviewRequestJson()))
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns201ForPdfWizardSaveWithWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/save")
                .file(pdfFile())
                .file(jsonPart("request", pdfSaveRequestJson()))
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("statementformats:write")))
        .andExpect(status().isCreated());
  }

  @Test
  void returns403ForPdfWizardSaveWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/save")
                .file(pdfFile())
                .file(jsonPart("request", pdfSaveRequestJson()))
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForAdminRead() throws Exception {
    mockMvc
        .perform(get("/v1/statement-formats").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void returns201ForAdminWrite() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(ClaimsHeaderTestBuilder.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createValidFormatJson()))
        .andExpect(status().isCreated());
  }

  private String createValidFormatJson() {
    return """
        {
          "displayName": "New Bank - Export",
          "formatType": "CSV",
          "bankName": "New Bank",
          "defaultCurrencyIsoCode": "USD",
          "dateHeader": "Date",
          "dateFormat": "MM/dd/uu",
          "descriptionHeader": "Description",
          "creditHeader": "Amount",
          "debitHeader": "Amount"
        }
        """;
  }

  private MockMultipartFile csvFile() {
    return new MockMultipartFile(
        "file",
        "sample.csv",
        "text/csv",
        """
        Date,Description,Amount,Type
        04/12/24,Coffee Shop,4.50,Debit
        """
            .getBytes());
  }

  private MockMultipartFile pdfFile() throws IOException {
    return new MockMultipartFile("file", "sample.pdf", "application/pdf", pdfContent());
  }

  private MockMultipartFile jsonPart(String name, String content) {
    return new MockMultipartFile(name, "", MediaType.APPLICATION_JSON_VALUE, content.getBytes());
  }

  private String pdfPreviewRequestJson() {
    return """
        {
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "headerMustContain": ["Date", "Description", "Amount"],
          "minimumRows": 1,
          "yearSource": "EXPLICIT_DATE",
          "mapping": {
            "dateHeader": "Date",
            "dateFormat": "MM/dd/uuuu",
            "descriptionHeader": "Description",
            "amountMode": "SIGNED_AMOUNT",
            "amountHeader": "Amount",
            "negativeMeans": "CREDIT"
          }
        }
        """;
  }

  private String pdfSaveRequestJson() {
    return """
        {
          "displayName": "Example PDF",
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "headerMustContain": ["Date", "Description", "Amount"],
          "minimumRows": 1,
          "yearSource": "EXPLICIT_DATE",
          "mapping": {
            "dateHeader": "Date",
            "dateFormat": "MM/dd/uuuu",
            "descriptionHeader": "Description",
            "amountMode": "SIGNED_AMOUNT",
            "amountHeader": "Amount",
            "negativeMeans": "CREDIT"
          }
        }
        """;
  }

  private byte[] pdfContent() throws IOException {
    try (var pdDocument = new PDDocument()) {
      var pdPage = new PDPage();
      pdDocument.addPage(pdPage);
      var pdType1Font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (var pdPageContentStream = new PDPageContentStream(pdDocument, pdPage)) {
        writePdfRow(
            pdPageContentStream, pdType1Font, List.of("Date", "Description", "Amount"), 750F);
        writePdfRow(
            pdPageContentStream, pdType1Font, List.of("01/02/2025", "Coffee Shop", "$4.50"), 734F);
      }
      var byteArrayOutputStream = new ByteArrayOutputStream();
      pdDocument.save(byteArrayOutputStream);
      return byteArrayOutputStream.toByteArray();
    }
  }

  private void writePdfRow(
      PDPageContentStream pdPageContentStream,
      PDType1Font pdType1Font,
      List<String> values,
      float y)
      throws IOException {
    var horizontalCoordinates = List.of(DATE_X, DESCRIPTION_X, AMOUNT_X);
    for (var index = 0; index < values.size(); index++) {
      pdPageContentStream.beginText();
      pdPageContentStream.setFont(pdType1Font, FONT_SIZE);
      pdPageContentStream.newLineAtOffset(horizontalCoordinates.get(index), y);
      pdPageContentStream.showText(values.get(index));
      pdPageContentStream.endText();
    }
  }
}
