package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClaimsSecurityConfig.class)
class TransactionOpenApiIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void crossUserSearchOpenApiDocumentsPagedTransactionResponse() throws Exception {
    var openApiJsonNode = readOpenApiDocument();

    var searchOperationJsonNode = openApiJsonNode.at("/paths/~1v1~1transactions~1search/get");

    assertThat(searchOperationJsonNode.isMissingNode()).isFalse();
    assertThat(openApiJsonNode.at("/components/schemas/TransactionResponse").isMissingNode())
        .isFalse();
    assertThat(openApiJsonNode.at("/components/schemas/PageMetadataResponse").isMissingNode())
        .isFalse();

    var responseSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode,
            searchOperationJsonNode.at("/responses/200/content/application~1json/schema"));
    assertThat(responseSchemaJsonNode.isMissingNode()).isFalse();
    assertThat(responseSchemaJsonNode.at("/properties/content/type").asText()).isEqualTo("array");

    var transactionSchemaJsonNode =
        resolveSchemaNode(openApiJsonNode, responseSchemaJsonNode.at("/properties/content/items"));
    assertThat(transactionSchemaJsonNode.at("/properties/ownerId").isMissingNode()).isFalse();

    var pageMetadataSchemaJsonNode =
        resolveSchemaNode(openApiJsonNode, responseSchemaJsonNode.at("/properties/metadata"));
    assertThat(pageMetadataSchemaJsonNode.at("/properties/page").isMissingNode()).isFalse();
    assertThat(pageMetadataSchemaJsonNode.at("/properties/size").isMissingNode()).isFalse();
    assertThat(pageMetadataSchemaJsonNode.at("/properties/numberOfElements").isMissingNode())
        .isFalse();
    assertThat(pageMetadataSchemaJsonNode.at("/properties/totalElements").isMissingNode())
        .isFalse();
    assertThat(pageMetadataSchemaJsonNode.at("/properties/totalPages").isMissingNode()).isFalse();
    assertThat(pageMetadataSchemaJsonNode.at("/properties/first").isMissingNode()).isFalse();
    assertThat(pageMetadataSchemaJsonNode.at("/properties/last").isMissingNode()).isFalse();

    var parameterNames =
        StreamSupport.stream(searchOperationJsonNode.path("parameters").spliterator(), false)
            .map(parameterJsonNode -> parameterJsonNode.path("name").asText())
            .toList();
    assertThat(parameterNames)
        .contains(
            "page",
            "size",
            "sort",
            "ownerId",
            "id",
            "accountId",
            "bankName",
            "dateFrom",
            "dateTo",
            "currencyIsoCode",
            "minAmount",
            "maxAmount",
            "type",
            "description",
            "createdAfter",
            "createdBefore",
            "updatedAfter",
            "updatedBefore")
        .doesNotContain("filter");
  }

  @Test
  void crossUserSearchCountOpenApiDocumented() throws Exception {
    var openApiJsonNode = readOpenApiDocument();

    var countOperationJsonNode = openApiJsonNode.at("/paths/~1v1~1transactions~1search~1count/get");

    assertThat(countOperationJsonNode.isMissingNode()).isFalse();
  }

  @Test
  void adminTransactionsPathsAndSchemaAreGone() throws Exception {
    var openApiJsonNode = readOpenApiDocument();

    assertThat(openApiJsonNode.at("/paths/~1v1~1admin~1transactions").isMissingNode()).isTrue();
    assertThat(openApiJsonNode.at("/paths/~1v1~1admin~1transactions~1count").isMissingNode())
        .isTrue();
    assertThat(openApiJsonNode.at("/components/schemas/AdminTransactionResponse").isMissingNode())
        .isTrue();
  }

  @Test
  void duplicateDetectionEnhancementOpenApiSchemasAreDocumented() throws Exception {
    var openApiJsonNode = readOpenApiDocument();

    var previewOperationJsonNode = openApiJsonNode.at("/paths/~1v1~1transactions~1preview/post");
    var batchOperationJsonNode = openApiJsonNode.at("/paths/~1v1~1transactions~1batch/post");
    assertThat(previewOperationJsonNode.path("description").asText())
        .contains("ordered", "advisory duplicate metadata", "completed earlier files");
    assertThat(batchOperationJsonNode.path("description").asText())
        .contains(
            "different parser revisions",
            "share one statement format and account",
            "allowDuplicate",
            "separate file provenance",
            "zero-created source creates none",
            "roll back together",
            "duplicates intentionally imported");

    var previewTransactionSchemaJsonNode =
        openApiJsonNode.at("/components/schemas/PreviewTransactionResponse");
    var previewResponseSchemaJsonNode = openApiJsonNode.at("/components/schemas/PreviewResponse");
    assertThat(previewResponseSchemaJsonNode.at("/properties/files/type").asText())
        .isEqualTo("array");
    assertThat(previewResponseSchemaJsonNode.at("/properties/fileImport").isMissingNode()).isTrue();
    assertThat(previewResponseSchemaJsonNode.at("/properties/previewImportToken").isMissingNode())
        .isTrue();
    assertThat(requiredPropertyNames(previewResponseSchemaJsonNode)).containsExactly("files");

    var previewFileSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode, previewResponseSchemaJsonNode.at("/properties/files/items"));
    assertThat(previewFileSchemaJsonNode.at("/properties/fileImport").isMissingNode()).isFalse();
    assertThat(previewFileSchemaJsonNode.at("/properties/previewImportToken").isMissingNode())
        .isFalse();
    assertThat(previewFileSchemaJsonNode.at("/properties/contentHash").isMissingNode()).isTrue();
    assertThat(requiredPropertyNames(previewFileSchemaJsonNode))
        .contains(
            "sourceFile", "statementFormatId", "previewImportToken", "fileImport", "transactions");

    var previewRequestSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode,
            previewOperationJsonNode.at("/requestBody/content/multipart~1form-data/schema"));
    assertThat(previewRequestSchemaJsonNode.path("type").asText()).isEqualTo("array");
    assertThat(previewRequestSchemaJsonNode.at("/items/format").asText()).isEqualTo("binary");
    assertThat(previewRequestSchemaJsonNode.path("minItems").asInt()).isEqualTo(1);
    assertThat(previewRequestSchemaJsonNode.path("description").asText())
        .contains("Repeat the files multipart part");
    assertThat(previewOperationJsonNode.at("/requestBody/required").asBoolean()).isTrue();

    var fileImportStatusSchemaJsonNode =
        resolveSchemaNode(openApiJsonNode, previewFileSchemaJsonNode.at("/properties/fileImport"));
    assertThat(fileImportStatusSchemaJsonNode.at("/properties/alreadyImported").isMissingNode())
        .isFalse();
    assertThat(fileImportStatusSchemaJsonNode.at("/properties/warningCode").isMissingNode())
        .isFalse();
    assertThat(fileImportStatusSchemaJsonNode.at("/properties/previousImport").isMissingNode())
        .isFalse();
    assertThat(requiredPropertyNames(fileImportStatusSchemaJsonNode))
        .contains("alreadyImported")
        .doesNotContain("warningCode", "previousImport");
    var fileWarningCodeSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode, fileImportStatusSchemaJsonNode.at("/properties/warningCode"));
    assertThat(enumValues(fileWarningCodeSchemaJsonNode)).containsExactly("FILE_ALREADY_IMPORTED");
    assertThat(schemaAllowsNull(fileWarningCodeSchemaJsonNode)).isFalse();

    var previousImportSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode, fileImportStatusSchemaJsonNode.at("/properties/previousImport"));
    assertThat(previousImportSchemaJsonNode.at("/properties/originalFilename").isMissingNode())
        .isFalse();
    assertThat(previousImportSchemaJsonNode.at("/properties/importedAt").isMissingNode()).isFalse();
    assertThat(previousImportSchemaJsonNode.at("/properties/statementFormatId").isMissingNode())
        .isFalse();
    assertThat(previousImportSchemaJsonNode.at("/properties/accountId").isMissingNode()).isFalse();
    assertThat(previousImportSchemaJsonNode.at("/properties/transactionCount").isMissingNode())
        .isFalse();
    assertThat(requiredPropertyNames(previousImportSchemaJsonNode)).doesNotContain("accountId");
    assertThat(schemaAllowsNull(previousImportSchemaJsonNode.at("/properties/accountId")))
        .isFalse();

    assertThat(previewTransactionSchemaJsonNode.at("/properties/duplicate").isMissingNode())
        .isFalse();
    assertThat(previewTransactionSchemaJsonNode.at("/properties/duplicateReason").isMissingNode())
        .isFalse();
    assertThat(requiredPropertyNames(previewTransactionSchemaJsonNode))
        .contains(
            "date", "description", "amount", "type", "bankName", "currencyIsoCode", "duplicate")
        .doesNotContain("category", "accountId", "duplicateReason");
    assertThat(
            previewTransactionSchemaJsonNode
                .at("/properties/duplicate")
                .path("description")
                .asText())
        .contains("existing", "completed earlier source file", "not compared with each other");
    var duplicateReasonSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode, previewTransactionSchemaJsonNode.at("/properties/duplicateReason"));
    assertThat(enumValues(duplicateReasonSchemaJsonNode))
        .containsExactlyInAnyOrder("EXISTING_TRANSACTION", "IN_BATCH");
    assertThat(schemaAllowsNull(duplicateReasonSchemaJsonNode)).isFalse();

    var batchImportTransactionSchemaJsonNode =
        openApiJsonNode.at("/components/schemas/BatchImportTransactionRequest");
    var batchImportRequestSchemaJsonNode =
        openApiJsonNode.at("/components/schemas/BatchImportRequest");
    assertThat(batchImportRequestSchemaJsonNode.at("/properties/files/type").asText())
        .isEqualTo("array");
    assertThat(batchImportRequestSchemaJsonNode.at("/properties/files/minItems").asInt())
        .isEqualTo(1);
    assertThat(requiredPropertyNames(batchImportRequestSchemaJsonNode)).containsExactly("files");
    assertThat(
            batchImportRequestSchemaJsonNode.at("/properties/previewImportToken").isMissingNode())
        .isTrue();
    assertThat(batchImportRequestSchemaJsonNode.at("/properties/contentHash").isMissingNode())
        .isTrue();
    var batchImportFileRequestSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode, batchImportRequestSchemaJsonNode.at("/properties/files/items"));
    assertThat(requiredPropertyNames(batchImportFileRequestSchemaJsonNode))
        .contains("previewImportToken", "transactions");
    assertThat(
            batchImportFileRequestSchemaJsonNode
                .at("/properties/transactions/minItems")
                .isMissingNode())
        .isTrue();
    assertThat(
            batchImportFileRequestSchemaJsonNode
                .at("/properties/previewImportToken")
                .isMissingNode())
        .isFalse();
    assertThat(
            batchImportTransactionSchemaJsonNode.at("/properties/allowDuplicate").isMissingNode())
        .isFalse();
    assertThat(
            batchImportTransactionSchemaJsonNode
                .at("/properties/allowDuplicate")
                .path("description")
                .asText())
        .contains("existing transaction", "completed earlier file");

    var batchImportResponseSchemaJsonNode =
        openApiJsonNode.at("/components/schemas/BatchImportResponse");
    assertThat(
            batchImportResponseSchemaJsonNode.at("/properties/duplicatesSkipped").isMissingNode())
        .isFalse();
    assertThat(
            batchImportResponseSchemaJsonNode.at("/properties/duplicatesImported").isMissingNode())
        .isFalse();
    assertThat(batchImportResponseSchemaJsonNode.at("/properties/files/type").asText())
        .isEqualTo("array");
    assertThat(requiredPropertyNames(batchImportResponseSchemaJsonNode))
        .contains("created", "duplicatesSkipped", "duplicatesImported", "files");
    var batchImportFileResponseSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode, batchImportResponseSchemaJsonNode.at("/properties/files/items"));
    assertThat(requiredPropertyNames(batchImportFileResponseSchemaJsonNode))
        .contains(
            "sourceFile", "created", "duplicatesSkipped", "duplicatesImported", "transactions");
  }

  private JsonNode readOpenApiDocument() throws Exception {
    var responseBody =
        mockMvc
            .perform(get("/transaction-service/v3/api-docs").contextPath("/transaction-service"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(responseBody);
  }

  private JsonNode resolveSchemaNode(JsonNode openApiJsonNode, JsonNode schemaJsonNode) {
    if (schemaJsonNode.isMissingNode()) {
      return schemaJsonNode;
    }

    var schemaReference = schemaJsonNode.path("$ref").textValue();
    if (schemaReference == null) {
      return schemaJsonNode;
    }

    var schemaName = schemaReference.substring("#/components/schemas/".length());
    return openApiJsonNode.at("/components/schemas/" + escapeJsonPointerToken(schemaName));
  }

  private List<String> enumValues(JsonNode schemaJsonNode) {
    return StreamSupport.stream(schemaJsonNode.path("enum").spliterator(), false)
        .map(JsonNode::asText)
        .toList();
  }

  private List<String> requiredPropertyNames(JsonNode schemaJsonNode) {
    return StreamSupport.stream(schemaJsonNode.path("required").spliterator(), false)
        .map(JsonNode::asText)
        .toList();
  }

  private boolean schemaAllowsNull(JsonNode schemaJsonNode) {
    return schemaJsonNode.path("nullable").asBoolean(false)
        || schemaTypeIncludes(schemaJsonNode, "null")
        || enumIncludesNull(schemaJsonNode)
        || composedSchemaAllowsNull(schemaJsonNode, "oneOf")
        || composedSchemaAllowsNull(schemaJsonNode, "anyOf");
  }

  private boolean schemaTypeIncludes(JsonNode schemaJsonNode, String type) {
    var typeJsonNode = schemaJsonNode.path("type");
    if (typeJsonNode.isTextual()) {
      return type.equals(typeJsonNode.asText());
    }
    return StreamSupport.stream(typeJsonNode.spliterator(), false)
        .anyMatch(typeValueJsonNode -> type.equals(typeValueJsonNode.asText()));
  }

  private boolean enumIncludesNull(JsonNode schemaJsonNode) {
    return StreamSupport.stream(schemaJsonNode.path("enum").spliterator(), false)
        .anyMatch(JsonNode::isNull);
  }

  private boolean composedSchemaAllowsNull(JsonNode schemaJsonNode, String compositionKeyword) {
    return StreamSupport.stream(schemaJsonNode.path(compositionKeyword).spliterator(), false)
        .anyMatch(composedSchemaJsonNode -> schemaAllowsNull(composedSchemaJsonNode));
  }

  private String escapeJsonPointerToken(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }
}
