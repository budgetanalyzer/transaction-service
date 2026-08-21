package org.budgetanalyzer.transaction.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

class SavedViewControllerAuthorizationIntegrationTest extends ControllerIntegrationTestSupport {

  @Test
  void returns401WithoutAuthentication() throws Exception {
    mockMvc.perform(get("/v1/views")).andExpect(status().isUnauthorized());
  }

  @Test
  void returns200ForReadEndpointWithReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isOk());
  }

  @Test
  void returns403ForReadEndpointWithoutReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns201ForWriteEndpointWithWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createViewJson()))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.pinnedCount").value(0))
        .andExpect(jsonPath("$.excludedCount").value(0))
        .andExpect(jsonPath("$.transactionCount").value(0));
  }

  @Test
  void returns403ForWriteEndpointWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createViewJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns200ForBulkPinWithWritePermission() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var firstTransaction = persistTransaction(USER_ID, "Coffee");
    var secondTransaction = persistTransaction(USER_ID, "Groceries");

    mockMvc
        .perform(
            post("/v1/views/{id}/pin", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(idsJson(firstTransaction.getId(), secondTransaction.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(2))
        .andExpect(jsonPath("$.notFoundIds").isEmpty());
  }

  @Test
  void returnsPartialSuccessForBulkExclude() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var transaction = persistTransaction(USER_ID, "Coffee");
    var missingTransactionId = 999999L;

    mockMvc
        .perform(
            post("/v1/views/{id}/exclude", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(idsJson(transaction.getId(), missingTransactionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1))
        .andExpect(jsonPath("$.notFoundIds.length()").value(1))
        .andExpect(jsonPath("$.notFoundIds[0]").value(missingTransactionId));
  }

  @Test
  void returns400ForBulkPinWithEmptyIdList() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/{id}/pin", UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\": []}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns403ForBulkExcludeWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/{id}/exclude", UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\": [1]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns403ForDeleteEndpointWithoutDeletePermission() throws Exception {
    mockMvc
        .perform(
            delete("/v1/views/{id}", UUID.randomUUID())
                .with(
                    ClaimsHeaderTestBuilder.user(USER_ID)
                        .withPermissions("views:read", "views:write")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns204ForDeleteEndpointWithDeletePermission() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            delete("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:delete")))
        .andExpect(status().isNoContent());
  }

  @Test
  void returns403ForAdminReadWithoutViewPermission() throws Exception {
    mockMvc
        .perform(get("/v1/views").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns403ForAdminWriteWithoutViewPermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createViewJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns403ForAdminDeleteWithoutViewPermission() throws Exception {
    mockMvc
        .perform(delete("/v1/views/{id}", UUID.randomUUID()).with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isForbidden());
  }

  private String createViewJson() {
    return """
        {
          "name": "My View",
          "criteria": {},
          "openEnded": false
        }
        """;
  }

  private String idsJson(Long firstId, Long secondId) {
    return "{\"ids\": [" + firstId + ", " + secondId + "]}";
  }
}
