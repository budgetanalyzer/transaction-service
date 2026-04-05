package org.budgetanalyzer.transaction.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(
    value = AdminTransactionController.class,
    properties = {
      "spring.data.web.pageable.default-page-size=50",
      "spring.data.web.pageable.max-page-size=100"
    })
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class AdminTransactionControllerAuthorizationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TransactionService transactionService;

  @BeforeEach
  void setupServiceMocks() {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());
    when(transactionService.countNotDeleted(any())).thenReturn(0L);
  }

  @Test
  void noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/admin/transactions")).andExpect(status().isUnauthorized());
  }

  @Test
  void regularUserWithReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminUser_returns200() throws Exception {
    mockMvc
        .perform(get("/v1/admin/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }

  @Test
  void userWithAdminRoleButNoPermissions_returns200() throws Exception {
    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withRoles("ADMIN")
                        .withPermissions()))
        .andExpect(status().isOk());
  }

  @Test
  void countEndpoint_noAuthentication_returns401() throws Exception {
    mockMvc.perform(get("/v1/admin/transactions/count")).andExpect(status().isUnauthorized());
  }

  @Test
  void countEndpoint_regularUserWithReadPermission_returns403() throws Exception {
    mockMvc
        .perform(
            get("/v1/admin/transactions/count")
                .with(
                    ClaimsHeaderTestBuilder.user("usr_test123")
                        .withPermissions("transactions:read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void countEndpoint_adminUser_returns200() throws Exception {
    mockMvc
        .perform(get("/v1/admin/transactions/count").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());
  }
}
