package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.security.SecurityContextUtil;
import org.budgetanalyzer.service.security.test.JwtTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.domain.SavedView;
import org.budgetanalyzer.transaction.domain.ViewCriteria;
import org.budgetanalyzer.transaction.service.SavedViewService;

@WebMvcTest(SavedViewController.class)
@Import({ServletApiExceptionHandler.class, MethodSecurityTestConfig.class})
class SavedViewControllerAuthorizationTest {

  private static final Jwt ADMIN_JWT = JwtTestBuilder.admin().build();

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
  @WithMockUser(authorities = {"transactions:read"})
  void readEndpoint_withReadPermission_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/views").header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(authorities = {"accounts:read"})
  void readEndpoint_withoutReadPermission_returns403() throws Exception {
    mockMvc.perform(get("/v1/views")).andExpect(status().isForbidden());
  }

  // ==================== Write permission ====================

  @Test
  @WithMockUser(authorities = {"transactions:write"})
  void writeEndpoint_withWritePermission_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123")
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
  @WithMockUser(authorities = {"transactions:read"})
  void writeEndpoint_withoutWritePermission_returns403() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(csrf())
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
  @WithMockUser(authorities = {"transactions:read", "transactions:write"})
  void deleteEndpoint_withoutDeletePermission_returns403() throws Exception {
    mockMvc
        .perform(delete("/v1/views/" + UUID.randomUUID()).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(authorities = {"transactions:delete"})
  void deleteEndpoint_withDeletePermission_returns204() throws Exception {
    mockMvc
        .perform(
            delete("/v1/views/" + UUID.randomUUID())
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_test123"))
        .andExpect(status().isNoContent());
  }

  // ==================== Admin with full permissions (production JWT shape) ====================

  @Test
  void admin_readEndpoint_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/views")
                .with(
                    jwt().jwt(ADMIN_JWT).authorities(JwtTestBuilder.extractAuthorities(ADMIN_JWT)))
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456"))
        .andExpect(status().isOk());
  }

  @Test
  void admin_writeEndpoint_returns201() throws Exception {
    mockMvc
        .perform(
            post("/v1/views")
                .with(
                    jwt().jwt(ADMIN_JWT).authorities(JwtTestBuilder.extractAuthorities(ADMIN_JWT)))
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456")
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
        .perform(
            delete("/v1/views/" + UUID.randomUUID())
                .with(
                    jwt().jwt(ADMIN_JWT).authorities(JwtTestBuilder.extractAuthorities(ADMIN_JWT)))
                .with(csrf())
                .header(SecurityContextUtil.INTERNAL_USER_ID_HEADER, "usr_admin456"))
        .andExpect(status().isNoContent());
  }

  // ==================== Helpers ====================

  private SavedView createStubView() {
    var view = new SavedView();
    view.setName("Test View");
    view.setUserId("usr_test123");
    view.setCriteria(new ViewCriteria(null, null, null, null, null, null, null, null));
    return view;
  }
}
