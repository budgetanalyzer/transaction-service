package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.transaction.domain.TransactionType;

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
  void savesViewAsWithExactTargetDefinitionLocationAndResolvedCounts() throws Exception {
    var sourceView = persistSavedView(USER_ID);
    var pinnedTransaction = persistTransaction(USER_ID, "Coffee");
    pinnedTransaction.setAccountId("checking-1");
    transactionRepository.save(pinnedTransaction);
    var excludedTransaction = persistTransaction(USER_ID, "Groceries");
    sourceView.pinTransaction(pinnedTransaction.getId());
    sourceView.excludeTransaction(excludedTransaction.getId());
    savedViewRepository.save(sourceView);

    var result =
        mockMvc
            .perform(
                post("/transaction-service/v1/views/{sourceViewId}/save-as", sourceView.getId())
                    .contextPath("/transaction-service")
                    .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(saveViewAsJson()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Coffee in 2024"))
            .andExpect(jsonPath("$.openEnded").value(true))
            .andExpect(jsonPath("$.pinnedCount").value(1))
            .andExpect(jsonPath("$.excludedCount").value(1))
            .andExpect(jsonPath("$.transactionCount").value(1))
            .andReturn();

    var targetViews =
        savedViewRepository.findAll().stream()
            .filter(savedView -> !savedView.getId().equals(sourceView.getId()))
            .toList();
    assertThat(targetViews).hasSize(1);
    var targetView = targetViews.get(0);
    assertThat(targetView.getUserId()).isEqualTo(USER_ID);
    assertThat(targetView.getName()).isEqualTo("Coffee in 2024");
    assertThat(targetView.isOpenEnded()).isTrue();
    assertThat(targetView.getCriteria().dateFrom()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(targetView.getCriteria().dateTo()).isEqualTo(LocalDate.of(2024, 12, 31));
    assertThat(targetView.getCriteria().accountIds()).containsExactly("checking-1");
    assertThat(targetView.getCriteria().bankNames()).containsExactly("Test Bank");
    assertThat(targetView.getCriteria().currencyIsoCodes()).containsExactly("USD");
    assertThat(targetView.getCriteria().minAmount()).isEqualByComparingTo(new BigDecimal("4.00"));
    assertThat(targetView.getCriteria().maxAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
    assertThat(targetView.getCriteria().type()).isEqualTo(TransactionType.DEBIT);
    assertThat(targetView.getCriteria().searchText()).isEqualTo("Coffee");
    assertThat(targetView.getPinnedIds()).containsExactly(pinnedTransaction.getId());
    assertThat(targetView.getExcludedIds()).containsExactly(excludedTransaction.getId());
    assertThat(targetView.getId()).isNotEqualTo(sourceView.getId());
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("http://localhost/transaction-service/v1/views/" + targetView.getId());
  }

  @Test
  void returns401ForSaveAsWithoutAuthentication() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/{sourceViewId}/save-as", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createViewJson()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void returns403ForSaveAsWithoutWritePermission() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/{sourceViewId}/save-as", UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createViewJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void returns400ForInvalidSaveAsTargetDefinition() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/{sourceViewId}/save-as", UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \" \", \"criteria\": null, \"openEnded\": false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'criteria')]").exists());
  }

  @Test
  void returnsOwnerScoped404ForForeignSaveAsSource() throws Exception {
    var sourceView = persistSavedView(OTHER_USER_ID);

    mockMvc
        .perform(
            post("/v1/views/{sourceViewId}/save-as", sourceView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createViewJson()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));
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

  private String saveViewAsJson() {
    return """
        {
          "name": "Coffee in 2024",
          "criteria": {
            "dateFrom": "2024-01-01",
            "dateTo": "2024-12-31",
            "accountIds": ["checking-1"],
            "bankNames": ["Test Bank"],
            "currencyIsoCodes": ["USD"],
            "minAmount": 4.00,
            "maxAmount": 5.00,
            "type": "DEBIT",
            "searchText": "Coffee"
          },
          "openEnded": true
        }
        """;
  }

  private String idsJson(Long firstId, Long secondId) {
    return "{\"ids\": [" + firstId + ", " + secondId + "]}";
  }
}
