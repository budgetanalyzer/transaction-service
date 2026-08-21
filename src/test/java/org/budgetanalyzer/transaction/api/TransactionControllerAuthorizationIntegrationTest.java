package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.transaction.domain.ParserRevision;
import org.budgetanalyzer.transaction.service.PreviewImportTokenService;

class TransactionControllerAuthorizationIntegrationTest extends ControllerIntegrationTestSupport {

  @Autowired private PreviewImportTokenService previewImportTokenService;

  @Test
  void returns401WithoutAuthentication() throws Exception {
    mockMvc.perform(get("/v1/transactions")).andExpect(status().isUnauthorized());
  }

  @Test
  void returns200ForReadEndpointWithReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForReadEndpointWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForWriteEndpointWithWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchImportJson(USER_ID)))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForDeleteEndpointWithoutDeletePermission() throws Exception {
    mockMvc
        .perform(
            delete("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForBulkDeleteWithDeletePermission() throws Exception {
    var firstTransaction = persistTransaction(USER_ID, "Coffee");
    var secondTransaction = persistTransaction(USER_ID, "Groceries");

    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:delete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(idsJson(firstTransaction.getId(), secondTransaction.getId())))
        .andExpect(status().isOk());
  }

  @Test
  void returns200ForPreviewWithReadPermission() throws Exception {
    var parserRevision = persistCsvStatementFormat(USER_ID);

    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile())
                .param("statementFormatId", parserRevision.getStatementFormat().getId().toString())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForPreviewWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            multipart("/v1/transactions/preview")
                .file(csvFile())
                .param("statementFormatId", "1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns403ForWriteEndpointWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchRequestWithToken("preview-token")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForAdminRead() throws Exception {
    mockMvc
        .perform(get("/v1/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void returns200ForAdminWrite() throws Exception {
    mockMvc
        .perform(
            post("/v1/transactions/batch")
                .with(ClaimsHeaderTestBuilder.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchImportJson(ADMIN_USER_ID)))
        .andExpect(status().isOk());
  }

  @Test
  void returns204ForAdminDelete() throws Exception {
    var transaction = persistTransaction(OTHER_USER_ID, "Coffee");

    mockMvc
        .perform(
            delete("/v1/transactions/{id}", transaction.getId())
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns200ForCountWithReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/count")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForCountWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/count")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns403ForGetByIdWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForOwnTransactionWithReadPermission() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");

    mockMvc
        .perform(
            get("/v1/transactions/{id}", transaction.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns404ForOtherUsersTransactionWithReadPermission() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");

    mockMvc
        .perform(
            get("/v1/transactions/{id}", transaction.getId())
                .with(
                    ClaimsHeaderTestBuilder.user(OTHER_USER_ID)
                        .withPermissions("transactions:read")))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns200ForAdminReadingAnyTransaction() throws Exception {
    var transaction = persistTransaction(OTHER_USER_ID, "Coffee");

    mockMvc
        .perform(
            get("/v1/transactions/{id}", transaction.getId()).with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForUpdateWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            patch("/v1/transactions/1")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForOwnTransactionUpdateWithWritePermission() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");

    mockMvc
        .perform(
            patch("/v1/transactions/{id}", transaction.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void returns404ForOtherUsersTransactionUpdateWithWritePermission() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");

    mockMvc
        .perform(
            patch("/v1/transactions/{id}", transaction.getId())
                .with(
                    ClaimsHeaderTestBuilder.user(OTHER_USER_ID)
                        .withPermissions("transactions:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns204ForOwnTransactionDeleteWithDeletePermission() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");

    mockMvc
        .perform(
            delete("/v1/transactions/{id}", transaction.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:delete")))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns404ForOtherUsersTransactionDeleteWithDeletePermission() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");

    mockMvc
        .perform(
            delete("/v1/transactions/{id}", transaction.getId())
                .with(
                    ClaimsHeaderTestBuilder.user(OTHER_USER_ID)
                        .withPermissions("transactions:delete")))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns401ForSearchWithoutAuthentication() throws Exception {
    mockMvc.perform(get("/v1/transactions/search")).andExpect(status().isUnauthorized());
  }

  @Test
  void returns403ForSearchWithReadOnly() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForSearchWithReadAnyOnly() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read:any")))
        .andExpect(status().isOk());
  }

  @Test
  void returns200ForSearchWithReadAndReadAny() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("transactions:read", "transactions:read:any")))
        .andExpect(status().isOk());
  }

  @Test
  void returns401ForSearchCountWithoutAuthentication() throws Exception {
    mockMvc.perform(get("/v1/transactions/search/count")).andExpect(status().isUnauthorized());
  }

  @Test
  void returns403ForSearchCountWithReadOnly() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search/count")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForSearchCountWithReadAnyOnly() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search/count")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read:any")))
        .andExpect(status().isOk());
  }

  @Test
  void returns200ForSearchCountWithReadAndReadAny() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/search/count")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("transactions:read", "transactions:read:any")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForGetByIdWithReadAnyOnly() throws Exception {
    mockMvc
        .perform(
            get("/v1/transactions/1")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:read:any")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForOtherUsersTransactionWithReadAndReadAny() throws Exception {
    var transaction = persistTransaction(OTHER_USER_ID, "Coffee");

    mockMvc
        .perform(
            get("/v1/transactions/{id}", transaction.getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("transactions:read", "transactions:read:any")))
        .andExpect(status().isOk());
  }

  @Test
  void returns200ForOtherUsersTransactionUpdateWithWriteAndWriteAny() throws Exception {
    var transaction = persistTransaction(OTHER_USER_ID, "Coffee");

    mockMvc
        .perform(
            patch("/v1/transactions/{id}", transaction.getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("transactions:write", "transactions:write:any"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"Updated\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void returns204ForOtherUsersTransactionDeleteWithDeleteAndDeleteAny() throws Exception {
    var transaction = persistTransaction(OTHER_USER_ID, "Coffee");

    mockMvc
        .perform(
            delete("/v1/transactions/{id}", transaction.getId())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("transactions:delete", "transactions:delete:any")))
        .andExpect(status().isNoContent());

    assertThat(transactionRepository.findByIdNotDeleted(transaction.getId())).isEmpty();
  }

  @Test
  void treatsOtherUsersIdsAsNotFoundForBulkDeleteWithDeleteOnly() throws Exception {
    var ownTransaction = persistTransaction(USER_ID, "Coffee");
    var otherTransaction = persistTransaction(OTHER_USER_ID, "Groceries");

    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:delete"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(idsJson(ownTransaction.getId(), otherTransaction.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(1))
        .andExpect(jsonPath("$.notFoundIds.length()").value(1))
        .andExpect(jsonPath("$.notFoundIds[0]").value(otherTransaction.getId()));
  }

  @Test
  void deletesAllIdsForBulkDeleteWithDeleteAndDeleteAny() throws Exception {
    var ownTransaction = persistTransaction(USER_ID, "Coffee");
    var otherTransaction = persistTransaction(OTHER_USER_ID, "Groceries");

    mockMvc
        .perform(
            post("/v1/transactions/bulk-delete")
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("transactions:delete", "transactions:delete:any"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(idsJson(ownTransaction.getId(), otherTransaction.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(2))
        .andExpect(jsonPath("$.notFoundIds").isEmpty());
  }

  private MockMultipartFile csvFile() {
    return new MockMultipartFile(
        "files",
        "test.csv",
        "text/csv",
        "Date,Description,Amount,Type\n2024-01-15,Coffee,4.50,Debit".getBytes());
  }

  private String batchImportJson(String ownerId) {
    var parserRevision = persistCsvStatementFormat(ownerId);
    var previewImportToken = createPreviewImportToken(ownerId, parserRevision);
    return batchRequestWithToken(previewImportToken);
  }

  private String createPreviewImportToken(String ownerId, ParserRevision parserRevision) {
    return previewImportTokenService.createToken(
        ownerId,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "statement.csv",
        parserRevision.getStatementFormat().getId(),
        parserRevision.getId(),
        null,
        1024L);
  }

  private String batchRequestWithToken(String previewImportToken) {
    return """
        {
          "files": [
            {
              "previewImportToken": "%s",
              "transactions": [
                {
                  "date": "2024-01-15",
                  "description": "Coffee",
                  "amount": 4.50,
                  "type": "DEBIT",
                  "bankName": "Test Bank",
                  "currencyIsoCode": "USD"
                }
              ]
            }
          ]
        }
        """
        .formatted(previewImportToken);
  }

  private String idsJson(Long firstId, Long secondId) {
    return "{\"ids\": [" + firstId + ", " + secondId + "]}";
  }
}
