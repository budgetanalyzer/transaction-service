package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

class StatementFormatControllerIntegrationTest extends ControllerIntegrationTestSupport {

  private static final float FONT_SIZE = 10F;
  private static final float DATE_X = 50F;
  private static final float DESCRIPTION_X = 150F;
  private static final float AMOUNT_X = 400F;

  @Test
  void returnsPersistedFormatsAndHiddenState() throws Exception {
    var visibleParserRevision = persistCsvStatementFormat(USER_ID);
    var hiddenParserRevision = persistCsvStatementFormat(USER_ID);

    mockMvc
        .perform(
            post(
                    "/v1/statement-formats/{id}/hide",
                    hiddenParserRevision.getStatementFormat().getId())
                .with(writeUser()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/v1/statement-formats").with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(visibleParserRevision.getStatementFormat().getId()))
        .andExpect(jsonPath("$[0].displayName").value("Test CSV"))
        .andExpect(jsonPath("$[0].bankName").value("Test Bank"))
        .andExpect(jsonPath("$[0].hidden").value(false));

    mockMvc
        .perform(get("/v1/statement-formats").param("includeHidden", "true").with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(
            jsonPath(
                    "$[?(@.id == "
                        + hiddenParserRevision.getStatementFormat().getId()
                        + ")].hidden")
                .value(true));
  }

  @Test
  void returnsPersistedFormatResponseContract() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var statementFormat = parserRevision.getStatementFormat();

    mockMvc
        .perform(get("/v1/statement-formats/{id}", statementFormat.getId()).with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(statementFormat.getId()))
        .andExpect(jsonPath("$.displayName").value("Test CSV"))
        .andExpect(jsonPath("$.formatType").value("CSV"))
        .andExpect(jsonPath("$.defaultCurrencyIsoCode").value("USD"))
        .andExpect(jsonPath("$.scope").value("USER"))
        .andExpect(jsonPath("$.ownerId").value(USER_ID))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.hidden").doesNotExist())
        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
  }

  @Test
  void returnsNotFoundContractForMissingFormat() throws Exception {
    mockMvc
        .perform(get("/v1/statement-formats/999").with(readUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));
  }

  @Test
  void createsCsvFormatWithLocationAndParserRevision() throws Exception {
    var result =
        mockMvc
            .perform(
                post("/v1/statement-formats")
                    .with(writeUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createFormatJson("Example CSV", null)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.displayName").value("Example CSV"))
            .andExpect(jsonPath("$.bankName").value("Example Bank"))
            .andExpect(jsonPath("$.ownerId").value(USER_ID))
            .andReturn();

    var location = result.getResponse().getHeader("Location");
    var statementFormatId = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));

    assertThat(statementFormatRepository.findById(statementFormatId)).isPresent();
    assertThat(parserRevisionRepository.findAll())
        .singleElement()
        .satisfies(
            parserRevision ->
                assertThat(parserRevision.getStatementFormat().getId())
                    .isEqualTo(statementFormatId));
  }

  @Test
  void returnsBusinessErrorWhenUserCreatesSystemFormat() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createFormatJson("System CSV", "SYSTEM")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("FORMAT_NOT_SUPPORTED"));

    assertThat(statementFormatRepository.findAll()).isEmpty();
  }

  @Test
  void returnsValidationFieldsForInvalidCreateRequest() throws Exception {
    mockMvc
        .perform(
            post("/v1/statement-formats")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\":\"Bank\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'displayName')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'formatType')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'defaultCurrencyIsoCode')]").exists());

    assertThat(statementFormatRepository.findAll()).isEmpty();
  }

  @Test
  void updatesPersistedFormatAndReturnsResponse() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var statementFormatId = parserRevision.getStatementFormat().getId();

    mockMvc
        .perform(
            put("/v1/statement-formats/{id}", statementFormatId)
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "displayName": "Updated CSV",
                      "bankName": "Updated Bank",
                      "defaultCurrencyIsoCode": "eur",
                      "enabled": false
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Updated CSV"))
        .andExpect(jsonPath("$.bankName").value("Updated Bank"))
        .andExpect(jsonPath("$.defaultCurrencyIsoCode").value("EUR"))
        .andExpect(jsonPath("$.enabled").value(false));

    var updated = statementFormatRepository.findById(statementFormatId).orElseThrow();
    assertThat(updated.getDisplayName()).isEqualTo("Updated CSV");
    assertThat(updated.isEnabled()).isFalse();
  }

  @Test
  void returnsNotFoundContractForMissingUpdateAndPreferenceTargets() throws Exception {
    mockMvc
        .perform(
            put("/v1/statement-formats/999")
                .with(writeUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\":\"Updated Bank\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));

    mockMvc
        .perform(post("/v1/statement-formats/999/hide").with(writeUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));

    mockMvc
        .perform(post("/v1/statement-formats/999/unhide").with(writeUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));
  }

  @Test
  void unhideRestoresFormatToDefaultList() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);
    var statementFormatId = parserRevision.getStatementFormat().getId();

    mockMvc
        .perform(post("/v1/statement-formats/{id}/hide", statementFormatId).with(writeUser()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post("/v1/statement-formats/{id}/unhide", statementFormatId).with(writeUser()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/v1/statement-formats").with(readUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(statementFormatId))
        .andExpect(jsonPath("$[0].hidden").value(false));
  }

  @Test
  void csvWizardReturnsAnalysisPreviewAndCreatedFormatContracts() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/analyze").file(csvFile()).with(writeUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headers[0]").value("Date"))
        .andExpect(jsonPath("$.inferredMapping.dateColumn").value("Date"));

    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/preview")
                .file(csvFile())
                .file(jsonPart("request", csvPreviewRequestJson()))
                .with(writeUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactions.length()").value(1))
        .andExpect(jsonPath("$.transactions[0].description").value("Coffee Shop"))
        .andExpect(jsonPath("$.transactions[0].accountId").value("checking-001"));

    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/save")
                .file(csvFile())
                .file(jsonPart("request", csvSaveRequestJson()))
                .with(writeUser()))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("/v1/statement-formats/")))
        .andExpect(jsonPath("$.displayName").value("Wizard CSV"))
        .andExpect(jsonPath("$.formatType").value("CSV"));

    assertThat(statementFormatRepository.findAll()).singleElement();
    assertThat(parserRevisionRepository.findAll()).singleElement();
  }

  @Test
  void csvWizardSeparatesRequestValidationFromBusinessValidation() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/preview")
                .file(csvFile())
                .file(
                    jsonPart(
                        "request",
                        "{\"bankName\":\"Example Bank\",\"defaultCurrencyIsoCode\":\"USD\"}"))
                .with(writeUser()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("mapping"));

    mockMvc
        .perform(
            multipart("/v1/statement-formats/csv-wizard/preview")
                .file(csvFile())
                .file(jsonPart("request", invalidCsvMappingRequestJson()))
                .with(writeUser()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("CSV_WIZARD_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("mapping.typeColumn"));
  }

  @Test
  void pdfWizardReturnsAnalysisPreviewAndCreatedFormatContracts() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/analyze").file(pdfFile()).with(writeUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidates.length()").value(1))
        .andExpect(jsonPath("$.candidates[0].inferredMapping.dateHeader").value("Date"));

    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/preview")
                .file(pdfFile())
                .file(jsonPart("request", pdfPreviewRequestJson()))
                .with(writeUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactions.length()").value(1))
        .andExpect(jsonPath("$.transactions[0].description").value("Coffee Shop"));

    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/save")
                .file(pdfFile())
                .file(jsonPart("request", pdfSaveRequestJson()))
                .with(writeUser()))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("/v1/statement-formats/")))
        .andExpect(jsonPath("$.displayName").value("Wizard PDF"))
        .andExpect(jsonPath("$.formatType").value("PDF"));

    assertThat(statementFormatRepository.findAll()).singleElement();
    assertThat(parserRevisionRepository.findAll()).singleElement();
  }

  @Test
  void pdfWizardSeparatesRequestValidationFromBusinessValidation() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/preview")
                .file(pdfFile())
                .file(
                    jsonPart(
                        "request",
                        pdfPreviewRequestJson().replace("\"yearSource\": \"EXPLICIT_DATE\",", "")))
                .with(writeUser()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("yearSource"));

    mockMvc
        .perform(
            multipart("/v1/statement-formats/pdf-wizard/preview")
                .file(pdfFile())
                .file(
                    jsonPart(
                        "request", pdfPreviewRequestJson().replace("\"Amount\"", "\"Missing\"")))
                .with(writeUser()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("PDF_WIZARD_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("mapping"));
  }

  private RequestPostProcessor readUser() {
    return ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:read");
  }

  private RequestPostProcessor writeUser() {
    return ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("statementformats:write");
  }

  private String createFormatJson(String displayName, String scope) {
    var scopeProperty = scope == null ? "" : "\"scope\": \"" + scope + "\",";
    return """
        {
          "displayName": "%s",
          "formatType": "CSV",
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          %s
          "dateHeader": "Date",
          "dateFormat": "MM/dd/uu",
          "descriptionHeader": "Description",
          "creditHeader": "Amount",
          "debitHeader": "Amount",
          "typeHeader": "Type"
        }
        """
        .formatted(displayName, scopeProperty);
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

  private String csvPreviewRequestJson() {
    return """
        {
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "accountId": "checking-001",
          "mapping": {
            "dateColumn": "Date",
            "dateFormat": "MM/dd/uu",
            "descriptionColumn": "Description",
            "amountMode": "SINGLE_AMOUNT_WITH_TYPE",
            "amountColumn": "Amount",
            "typeColumn": "Type"
          }
        }
        """;
  }

  private String invalidCsvMappingRequestJson() {
    return csvPreviewRequestJson().replace("\"typeColumn\": \"Type\"", "\"typeColumn\": null");
  }

  private String csvSaveRequestJson() {
    return csvPreviewRequestJson()
        .replace("\"accountId\": \"checking-001\",", "\"displayName\": \"Wizard CSV\",");
  }

  private String pdfPreviewRequestJson() {
    return """
        {
          "bankName": "Example Bank",
          "defaultCurrencyIsoCode": "USD",
          "accountId": "checking-001",
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
    return pdfPreviewRequestJson()
        .replace("\"accountId\": \"checking-001\",", "\"displayName\": \"Wizard PDF\",");
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
