package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.transaction.domain.SavedView;

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

    var mvcResult =
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
            .andExpect(jsonPath("$.*", hasSize(5)))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.name").value("My View"))
            .andExpect(jsonPath("$.transactionCount").value(2))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.updatedAt").isNotEmpty())
            .andExpect(jsonPath("$.criteria").doesNotExist())
            .andExpect(jsonPath("$.pinnedCount").doesNotExist())
            .andReturn();

    var savedView = savedViewRepository.findAll().getFirst();
    assertThat(savedView.getUserId()).isEqualTo(USER_ID);
    assertThat(mvcResult.getResponse().getHeader("Location"))
        .endsWith("/v1/views/" + savedView.getId());
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
  void acceptsTenThousandCreateMembershipEntries() throws Exception {
    var transaction = persistTransaction(USER_ID, "Repeated create membership");

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Boundary\",\"transactionIds\":["
                        + repeatedTransactionIdsJson(transaction.getId(), 10_000)
                        + "]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.transactionCount").value(1));
  }

  @Test
  void rejectsCreateMembershipArrayAboveTenThousandEntries() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Oversized\",\"transactionIds\":["
                        + repeatedTransactionIdsJson(1L, 10_001)
                        + "]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.fieldErrors").value(nullValue()));
  }

  @Test
  void trimsCreatedViewName() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  Monthly review  \",\"transactionIds\":[]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Monthly review"));

    assertThat(savedViewRepository.findAll())
        .singleElement()
        .extracting(SavedView::getName)
        .isEqualTo("Monthly review");
  }

  @Test
  void listsAndGetsExactOwnerScopedMetadataWithActiveCounts() throws Exception {
    var transaction = persistTransaction(USER_ID, "Coffee");
    var savedView = persistSavedView(USER_ID);
    persistSavedView(OTHER_USER_ID);
    savedViewTransactionRepository.insertMissing(savedView.getId(), List.of(transaction.getId()));

    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(savedView.getId().toString()))
        .andExpect(jsonPath("$[0].transactionCount").value(1));

    mockMvc
        .perform(
            get("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.id").value(savedView.getId().toString()))
        .andExpect(jsonPath("$.name").value(savedView.getName()))
        .andExpect(jsonPath("$.transactionCount").value(1));
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
  void duplicateNameCreateReturnsSafeBusinessError() throws Exception {
    persistSavedView(USER_ID);

    var mvcResult =
        mockMvc
            .perform(
                post("/v1/views")
                    .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"  test view  \",\"transactionIds\":[]}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
            .andExpect(jsonPath("$.code").value("SAVED_VIEW_NAME_ALREADY_EXISTS"))
            .andExpect(jsonPath("$.fieldErrors").value(nullValue()))
            .andReturn();

    assertNoPersistenceDiagnostics(mvcResult.getResponse().getContentAsString());
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
  void acceptsTenThousandAddMembershipEntries() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var transaction = persistTransaction(USER_ID, "Repeated add membership");

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"addTransactionIds\":["
                        + repeatedTransactionIdsJson(transaction.getId(), 10_000)
                        + "],\"removeTransactionIds\":[]}"))
        .andExpect(status().isNoContent());

    assertThat(savedViewTransactionRepository.findTransactionIds(savedView.getId()))
        .containsExactly(transaction.getId());
  }

  @Test
  void rejectsAddMembershipArrayAboveTenThousandEntries() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"addTransactionIds\":["
                        + repeatedTransactionIdsJson(1L, 10_001)
                        + "],\"removeTransactionIds\":[]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.fieldErrors").value(nullValue()));
  }

  @Test
  void acceptsTenThousandRemoveMembershipEntries() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var transaction = persistTransaction(USER_ID, "Repeated remove membership");
    savedViewTransactionRepository.insertMissing(savedView.getId(), List.of(transaction.getId()));

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"addTransactionIds\":[],\"removeTransactionIds\":["
                        + repeatedTransactionIdsJson(transaction.getId(), 10_000)
                        + "]}"))
        .andExpect(status().isNoContent());

    assertThat(savedViewTransactionRepository.findTransactionIds(savedView.getId())).isEmpty();
  }

  @Test
  void rejectsRemoveMembershipArrayAboveTenThousandEntries() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"addTransactionIds\":[],\"removeTransactionIds\":["
                        + repeatedTransactionIdsJson(1L, 10_001)
                        + "]}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
        .andExpect(jsonPath("$.code").value("SAVED_VIEW_MEMBERSHIP_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.fieldErrors").value(nullValue()));
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
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  @Test
  void renameUsesPatchAndTrimsName() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  Renamed  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));

    assertThat(savedViewRepository.findById(savedView.getId()).orElseThrow().getName())
        .isEqualTo("Renamed");
  }

  @Test
  void duplicateNameRenameReturnsSafeBusinessErrorAndPreservesOriginalName() throws Exception {
    persistSavedView(USER_ID);
    var renamedView = new SavedView();
    renamedView.setUserId(USER_ID);
    renamedView.setName("Original name");
    renamedView = savedViewRepository.save(renamedView);

    var mvcResult =
        mockMvc
            .perform(
                patch("/v1/views/{id}", renamedView.getId())
                    .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"  TEST VIEW  \"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("APPLICATION_ERROR"))
            .andExpect(jsonPath("$.code").value("SAVED_VIEW_NAME_ALREADY_EXISTS"))
            .andExpect(jsonPath("$.fieldErrors").value(nullValue()))
            .andReturn();

    assertNoPersistenceDiagnostics(mvcResult.getResponse().getContentAsString());
    assertThat(savedViewRepository.findById(renamedView.getId()).orElseThrow().getName())
        .isEqualTo("Original name");
  }

  @Test
  void removedPutContractReturnsStandardMethodNotAllowedResponse() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            put("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Legacy update\"}"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(
            header()
                .string(
                    HttpHeaders.ALLOW,
                    allOf(
                        containsString("GET"), containsString("PATCH"), containsString("DELETE"))))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"));
  }

  @Test
  void removedPinAndExcludeRoutesReturnStandardNotFoundResponses() throws Exception {
    var savedViewId = UUID.randomUUID();
    var transactionId = 123L;
    var removedRouteRequests =
        List.of(
            post("/v1/views/{id}/pin", savedViewId),
            post("/v1/views/{id}/exclude", savedViewId),
            post("/v1/views/{id}/pin/{transactionId}", savedViewId, transactionId),
            delete("/v1/views/{id}/pin/{transactionId}", savedViewId, transactionId),
            post("/v1/views/{id}/exclude/{transactionId}", savedViewId, transactionId),
            delete("/v1/views/{id}/exclude/{transactionId}", savedViewId, transactionId));

    for (var removedRouteRequest : removedRouteRequests) {
      mockMvc
          .perform(
              removedRouteRequest.with(
                  ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.type").value("NOT_FOUND"));
    }
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
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  @Test
  void validatesCreateAndRenameNames() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \",\"transactionIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "x".repeat(256) + "\",\"transactionIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            patch("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  @Test
  void validatesCreateMembershipArrayAndElements() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Missing IDs\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Null ID\",\"transactionIds\":[null]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsMembershipDeltaWithoutAnyChanges() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[],\"removeTransactionIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  @Test
  void rejectsNullAndNonPositiveMembershipDeltaIds() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[null],\"removeTransactionIds\":[0,-1]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
  }

  @Test
  void staleMembershipDeltaReturnsGeneric422WithoutDisclosingIds() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var foreignTransaction = persistTransaction(OTHER_USER_ID, "Foreign");

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"addTransactionIds\":["
                        + foreignTransaction.getId()
                        + "],\"removeTransactionIds\":[]}"))
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
  void repeatedMembershipDeltaIsIdempotent() throws Exception {
    var savedView = persistSavedView(USER_ID);
    var transaction = persistTransaction(USER_ID, "Coffee");
    var requestBody =
        "{\"addTransactionIds\":[" + transaction.getId() + "],\"removeTransactionIds\":[]}";

    for (var requestNumber = 0; requestNumber < 2; requestNumber++) {
      mockMvc
          .perform(
              patch("/v1/views/{id}/transactions", savedView.getId())
                  .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestBody))
          .andExpect(status().isNoContent())
          .andExpect(content().string(""));
    }

    mockMvc
        .perform(
            get("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transactionIds", hasSize(1)))
        .andExpect(jsonPath("$.transactionIds[0]").value(transaction.getId()));
  }

  @Test
  void ownerIsolationReturns404ForEveryViewResourceOperation() throws Exception {
    var foreignSavedView = persistSavedView(OTHER_USER_ID);

    mockMvc
        .perform(
            get("/v1/views/{id}", foreignSavedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));

    mockMvc
        .perform(
            patch("/v1/views/{id}", foreignSavedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hidden\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));

    mockMvc
        .perform(
            get("/v1/views/{id}/transactions", foreignSavedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", foreignSavedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[1],\"removeTransactionIds\":[]}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));

    mockMvc
        .perform(
            delete("/v1/views/{id}", foreignSavedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:delete")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("NOT_FOUND"));
  }

  @Test
  void rejectsPermissionsThatDoNotMatchEachSavedViewOperation() throws Exception {
    var savedView = persistSavedView(USER_ID);

    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Denied\",\"transactionIds\":[]}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            patch("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Denied\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            patch("/v1/views/{id}/transactions", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"addTransactionIds\":[1],\"removeTransactionIds\":[]}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete("/v1/views/{id}", savedView.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("views:write")))
        .andExpect(status().isForbidden());
  }

  private void assertNoPersistenceDiagnostics(String responseBody) {
    assertThat(responseBody.toLowerCase(Locale.ROOT))
        .doesNotContain(
            "uq_saved_view_user_name_ci",
            "23505",
            "sqlstate",
            "insert into",
            "update saved_view",
            "dataintegrityviolationexception",
            "psqlexception",
            "sqlexception",
            "constraint",
            "duplicate key",
            "violates unique");
  }

  private String repeatedTransactionIdsJson(long transactionId, int entryCount) {
    return String.join(",", Collections.nCopies(entryCount, Long.toString(transactionId)));
  }
}
