package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isOk());
  }

  @Test
  void readEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(ClaimsHeaderTestBuilder.user("usr_test123").withPermissions("accounts:read")))
        .andExpect(status().isForbidden());
  }

  // ==================== Write permission ====================

  @Test
  void writeEndpoint_withWritePermission_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:write"))
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
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read"))
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

  // ==================== Delete permission ====================

  @Test
  void deleteEndpoint_withoutDeletePermission_returns403() throws Exception {
    mockMvc
        .perform(
            delete("/v1/views/" + UUID.randomUUID())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read", "transactions:write")))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteEndpoint_withDeletePermission_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/v1/views/" + UUID.randomUUID())
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:delete")))
        .andExpect(status().isNoContent());
  }

  // ==================== Admin with full permissions ====================

  @Test
  void admin_readEndpoint_returns200() throws Exception {
    mockMvc
        .perform(get("/v1/views").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void admin_writeEndpoint_returns201() throws Exception {
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
        .andExpect(status().isCreated());
  }

  @Test
  void admin_deleteEndpoint_returns204() throws Exception {
    mockMvc
        .perform(delete("/v1/views/" + UUID.randomUUID()).with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isNoContent());
  }

  // ==================== Helpers ====================

  private SavedView createStubView() {
    var view = new SavedView();
    view.setId(UUID.randomUUID());
    view.setName("Test View");
    view.setUserId("usr_test123");
    view.setCriteria(new ViewCriteria(null, null, null, null, null, null, null, null));
    return view;
  }
}
