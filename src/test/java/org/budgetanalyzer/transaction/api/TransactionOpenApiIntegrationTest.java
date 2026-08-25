package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class TransactionOpenApiIntegrationTest extends ControllerIntegrationTestSupport {

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
        .doesNotHaveDuplicates()
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

    assertThat(searchOperationJsonNode.path("description").asText())
        .contains(
            "stored numeric amount",
            "without currency normalization",
            "amount-only query",
            "independent exact criterion");
    assertThat(parameterNamed(searchOperationJsonNode, "minAmount").path("description").asText())
        .contains("stored numeric amount", "across currencies");
    assertThat(parameterNamed(searchOperationJsonNode, "maxAmount").path("description").asText())
        .contains("stored numeric amount", "across currencies");
    assertThat(
            parameterNamed(searchOperationJsonNode, "currencyIsoCode").path("description").asText())
        .contains("independent", "currency-specific");
    assertThat(parameterNamed(searchOperationJsonNode, "sort").path("description").asText())
        .contains("stored numeric amounts", "without currency normalization");
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
  void savedViewOpenApiContainsOnlyStaticMembershipContract() throws Exception {
    var openApiJsonNode = readOpenApiDocument();
    var collectionPathJsonNode = openApiJsonNode.at("/paths/~1v1~1views");
    var createOperationJsonNode = openApiJsonNode.at("/paths/~1v1~1views/post");
    var updatePathJsonNode = openApiJsonNode.at("/paths/~1v1~1views~1{id}");
    var membershipPathJsonNode = openApiJsonNode.at("/paths/~1v1~1views~1{id}~1transactions");

    assertThat(propertyNames(collectionPathJsonNode)).containsExactlyInAnyOrder("get", "post");
    assertThat(createOperationJsonNode.isMissingNode()).isFalse();
    assertThat(propertyNames(updatePathJsonNode))
        .containsExactlyInAnyOrder("get", "patch", "delete");
    assertThat(propertyNames(membershipPathJsonNode)).containsExactlyInAnyOrder("get", "patch");
    assertThat(openApiJsonNode.at("/paths/~1v1~1views~1{id}~1pin").isMissingNode()).isTrue();
    assertThat(openApiJsonNode.at("/paths/~1v1~1views~1{id}~1exclude").isMissingNode()).isTrue();
    assertThat(openApiJsonNode.at("/paths/~1v1~1views~1{id}~1pin~1{txnId}").isMissingNode())
        .isTrue();
    assertThat(openApiJsonNode.at("/paths/~1v1~1views~1{id}~1exclude~1{txnId}").isMissingNode())
        .isTrue();

    var createRequestSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode,
            createOperationJsonNode.at("/requestBody/content/application~1json/schema"));
    assertThat(requiredPropertyNames(createRequestSchemaJsonNode))
        .containsExactlyInAnyOrder("name", "transactionIds");
    assertThat(propertyNames(createRequestSchemaJsonNode.path("properties")))
        .containsExactlyInAnyOrder("name", "transactionIds");
    assertThat(createRequestSchemaJsonNode.at("/properties/name/maxLength").asInt()).isEqualTo(255);
    var createTransactionIdsJsonNode = createRequestSchemaJsonNode.at("/properties/transactionIds");
    assertThat(createTransactionIdsJsonNode.path("type").asText()).isEqualTo("array");
    assertThat(createTransactionIdsJsonNode.at("/items/type").asText()).isEqualTo("integer");
    assertThat(createTransactionIdsJsonNode.at("/items/minimum").asLong()).isEqualTo(1);
    assertThat(createOperationJsonNode.at("/responses/201/headers/Location").isMissingNode())
        .isFalse();

    var updateRequestSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode,
            updatePathJsonNode.at("/patch/requestBody/content/application~1json/schema"));
    assertThat(requiredPropertyNames(updateRequestSchemaJsonNode)).containsExactly("name");
    assertThat(propertyNames(updateRequestSchemaJsonNode.path("properties")))
        .containsExactly("name");

    var membershipResponseSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode,
            membershipPathJsonNode.at("/get/responses/200/content/application~1json/schema"));
    assertThat(requiredPropertyNames(membershipResponseSchemaJsonNode))
        .containsExactly("transactionIds");
    assertThat(propertyNames(membershipResponseSchemaJsonNode.path("properties")))
        .containsExactly("transactionIds");
    assertThat(membershipResponseSchemaJsonNode.at("/properties/transactionIds/type").asText())
        .isEqualTo("array");

    var membershipDeltaSchemaJsonNode =
        resolveSchemaNode(
            openApiJsonNode,
            membershipPathJsonNode.at("/patch/requestBody/content/application~1json/schema"));
    assertThat(requiredPropertyNames(membershipDeltaSchemaJsonNode))
        .containsExactlyInAnyOrder("addTransactionIds", "removeTransactionIds");
    assertThat(propertyNames(membershipDeltaSchemaJsonNode.path("properties")))
        .containsExactlyInAnyOrder("addTransactionIds", "removeTransactionIds");
    for (var propertyName : List.of("addTransactionIds", "removeTransactionIds")) {
      var transactionIdsJsonNode =
          membershipDeltaSchemaJsonNode.path("properties").path(propertyName);
      assertThat(transactionIdsJsonNode.path("type").asText()).isEqualTo("array");
      assertThat(transactionIdsJsonNode.at("/items/type").asText()).isEqualTo("integer");
      assertThat(transactionIdsJsonNode.at("/items/minimum").asLong()).isEqualTo(1);
    }
    assertThat(membershipPathJsonNode.at("/patch/responses/204/content").isMissingNode()).isTrue();

    var savedViewSchemaJsonNode = openApiJsonNode.at("/components/schemas/SavedViewResponse");
    assertThat(propertyNames(savedViewSchemaJsonNode.path("properties")))
        .containsExactlyInAnyOrder("id", "name", "transactionCount", "createdAt", "updatedAt");
    assertThat(requiredPropertyNames(savedViewSchemaJsonNode))
        .containsExactlyInAnyOrder("id", "name", "transactionCount", "createdAt", "updatedAt");
    assertThat(openApiJsonNode.at("/components/schemas/ViewCriteriaApi").isMissingNode()).isTrue();
    assertThat(
            openApiJsonNode.at("/components/schemas/BulkViewTransactionResponse").isMissingNode())
        .isTrue();
    assertThat(openApiJsonNode.at("/components/schemas/BulkViewTransactionRequest").isMissingNode())
        .isTrue();
    assertThat(openApiJsonNode.at("/components/schemas/ViewTransactionResponse").isMissingNode())
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
    assertThat(previewRequestSchemaJsonNode.path("type").asText()).isEqualTo("object");
    assertThat(requiredPropertyNames(previewRequestSchemaJsonNode)).containsExactly("files");
    var previewFilesSchemaJsonNode = previewRequestSchemaJsonNode.at("/properties/files");
    assertThat(previewFilesSchemaJsonNode.path("type").asText()).isEqualTo("array");
    assertThat(previewFilesSchemaJsonNode.at("/items/format").asText()).isEqualTo("binary");
    assertThat(previewFilesSchemaJsonNode.path("minItems").asInt()).isEqualTo(1);
    assertThat(previewFilesSchemaJsonNode.path("description").asText())
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

  private JsonNode parameterNamed(JsonNode operationJsonNode, String parameterName) {
    return StreamSupport.stream(operationJsonNode.path("parameters").spliterator(), false)
        .filter(parameterJsonNode -> parameterName.equals(parameterJsonNode.path("name").asText()))
        .findFirst()
        .orElseThrow();
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

  private List<String> propertyNames(JsonNode jsonNode) {
    return StreamSupport.stream(((Iterable<String>) jsonNode::fieldNames).spliterator(), false)
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
