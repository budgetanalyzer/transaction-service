package org.budgetanalyzer.transaction.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
  void enforcesReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isOk());
  }

  @Test
  void createsStaticViewWithCanonicalMembership() throws Exception {
    var firstTransaction = persistTransaction(USER_ID, "Coffee");
    var secondTransaction = persistTransaction(USER_ID, "Groceries");

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"My View\",\"transactionIds\":["
                        + secondTransaction.getId()
                        + ","
                        + firstTransaction.getId()
                        + ","
                        + firstTransaction.getId()
                        + "]}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("My View"))
        .andExpect(jsonPath("$.transactionCount").value(2))
        .andExpect(jsonPath("$.criteria").doesNotExist())
        .andExpect(jsonPath("$.pinnedCount").doesNotExist());
  }

  @Test
  void allowsEmptyMembershipCreate() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Empty\",\"transactionIds\":[]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.transactionCount").value(0));
  }

  @Test
  void staleMembershipReturnsGeneric422WithoutDisclosingIds() throws Exception {
    var foreignTransaction = persistTransaction(OTHER_USER_ID, "Foreign");

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Rejected\",\"transactionIds\":["
                        + foreignTransaction.getId()
                        + "]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("SAVED_VIEW_MEMBERSHIP_STALE"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                            foreignTransaction.getId().toString()))));
  }

  @Test
  void membershipDeltaReturns204AndReadReturnsFlatIds() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var firstTransaction = persistTransaction(USER_ID, "Coffee");
    var secondTransaction = persistTransaction(USER_ID, "Groceries");

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"addTransactionIds\":["
                        + secondTransaction.getId()
                        + ","
                        + firstTransaction.getId()
                        + "],\"removeTransactionIds\":[]}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    mockMvc
        .perform(
            get("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactionIds[0]").value(firstTransaction.getId()))
        .andExpect(jsonPath("$.transactionIds[1]").value(secondTransaction.getId()))
        .andExpect(jsonPath("$.matched").doesNotExist());
  }

  @Test
  void rejectsOverlappingMembershipDelta() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[1],\"removeTransactionIds\":[1]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void renameUsesPatchAndNameOnly() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void enforcesDeletePermission() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            delete("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:delete")))
        .andExpect(status().isNoContent());
  }

  @Test
  void adminWithoutExplicitViewPermissionIsForbidden() throws Exception {
    mockMvc
        .perform(get("/v1/views").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isForbidden());
  }

  @Test
  void validatesRequiredArraysAndPositiveIds() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Invalid\",\"transactionIds\":[0]}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[]}"))
        .andExpect(status().isBadRequest());
  }
}
