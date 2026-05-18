package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.ViewCriteria;
import org.budgetanalyzer.transaction.service.SavedViewService;
import org.budgetanalyzer.transaction.service.SavedViewService.BulkViewUpdateResult;

@WebMvcTest(SavedViewController.class)
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class SavedViewControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SavedViewService savedViewService;

  @BeforeEach
  void setupServiceMocks() {
    var stubView = createStubView();
    when(savedViewService.createView(anyString(), any())).thenReturn(stubView);
    when(savedViewService.getViewsForUser(anyString())).thenReturn(List.of());
    when(savedViewService.countViewTransactions(any())).thenReturn(0L);
  }

  // ==================== No authentication ====================

  @Test
  void noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/views")).andExpect(status().isUnauthorized());
  }

  // ==================== Read permission ====================

  @Test
  void readEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:read")))
        .andExpect(status().isOk());
  }

  @Test
  void readEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:write")))
        .andExpect(status().isForbidden());
  }

  // ==================== Write permission ====================

  @Test
  void writeEndpoint_withWritePermission_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "My View",
                      "criteria": {},
                      "openEnded": false
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"));
  }

  @Test
  void writeEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "My View",
                      "criteria": {},
                      "openEnded": false
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void bulkPinEndpoint_withWritePermission_returns200() throws Exception {
    when(savedViewService.bulkPinTransactions(any(), anyString(), any()))
        .thenReturn(new BulkViewUpdateResult(2, List.of()));

    mockMvc
        .perform(
            post("/v1/views/" + UUID.randomUUID() + "/pin")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "ids": [1, 2]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(2))
        .andExpect(jsonPath("$.notFoundIds").isEmpty());
  }

  @Test
  void bulkExcludeEndpoint_partialSuccess_returns200WithNotFoundIds() throws Exception {
    when(savedViewService.bulkExcludeTransactions(any(), anyString(), any()))
        .thenReturn(new BulkViewUpdateResult(1, List.of(999L)));

    mockMvc
        .perform(
            post("/v1/views/" + UUID.randomUUID() + "/exclude")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "ids": [1, 999]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedCount").value(1))
        .andExpect(jsonPath("$.notFoundIds.length()").value(1))
        .andExpect(jsonPath("$.notFoundIds[0]").value(999));
  }

  @Test
  void bulkPinEndpoint_emptyIdList_returns400() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/" + UUID.randomUUID() + "/pin")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "ids": []
                    }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bulkExcludeEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/views/" + UUID.randomUUID() + "/exclude")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "ids": [1]
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  // ==================== Delete permission ====================

  @Test
  void deleteEndpoint_withoutDeletePermission_returns403() throws Exception {
    mockMvc
        .perform(
            delete("/v1/views/" + UUID.randomUUID())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("views:read", "views:write")))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteEndpoint_withDeletePermission_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/v1/views/" + UUID.randomUUID())
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("views:delete")))
        .andExpect(status().isNoContent());
  }

  // ==================== Admin without view permissions ====================

  @Test
  void admin_readEndpoint_returns403() throws Exception {
    mockMvc
        .perform(get("/v1/views").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isForbidden());
  }

  @Test
  void admin_writeEndpoint_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(ClaimsHeaderTestBuilder.admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "My View",
                      "criteria": {},
                      "openEnded": false
                    }
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void admin_deleteEndpoint_returns403() throws Exception {
    mockMvc
        .perform(delete("/v1/views/" + UUID.randomUUID()).with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isForbidden());
  }

  // ==================== Helpers ====================

  private SavedView createStubView() {
    var view = new SavedView();
    view.setId(UUID.randomUUID());
    view.setName("Test View");
    view.setUserId("usr_test123");
    view.setCriteria(ViewCriteria.empty());
    return view;
  }
}
