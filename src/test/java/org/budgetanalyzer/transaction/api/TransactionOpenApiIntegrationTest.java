package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

  private String escapeJsonPointerToken(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }
}
