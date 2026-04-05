package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.budgetanalyzer.service.security.ClaimsHeaderSecurityConfig;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;
import org.budgetanalyzer.service.servlet.api.ServletApiExceptionHandler;
import org.budgetanalyzer.transaction.api.request.TransactionFilter;
import org.budgetanalyzer.transaction.domain.Transaction;
import org.budgetanalyzer.transaction.domain.TransactionType;
import org.budgetanalyzer.transaction.service.TransactionService;

@WebMvcTest(
    value = AdminTransactionController.class,
    properties = {
      "spring.data.web.pageable.default-page-size=50",
      "spring.data.web.pageable.max-page-size=100"
    })
@Import({ServletApiExceptionHandler.class, ClaimsHeaderSecurityConfig.class})
class AdminTransactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private TransactionService transactionService;

  @Test
  void searchTransactions_paginationParams_bindCorrectly() throws Exception {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .param("page", "2")
                .param("size", "25")
                .param("sort", "amount,asc")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());

    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(transactionService).search(any(), pageableCaptor.capture());
    var pageable = pageableCaptor.getValue();
    assertThat(pageable.getPageNumber()).isEqualTo(2);
    assertThat(pageable.getPageSize()).isEqualTo(25);
    var amountSort = pageable.getSort().getOrderFor("amount");
    assertThat(amountSort).isNotNull();
    assertThat(amountSort.getDirection()).isEqualTo(Sort.Direction.ASC);
  }

  @Test
  void searchTransactions_noSortParam_usesDefaultSort() throws Exception {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc
        .perform(get("/v1/admin/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());

    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(transactionService).search(any(), pageableCaptor.capture());
    var pageable = pageableCaptor.getValue();
    assertThat(pageable.getPageSize()).isEqualTo(50);
    var sortOrders = pageable.getSort().toList();
    assertThat(sortOrders).hasSize(2);
    assertThat(sortOrders.get(0).getProperty()).isEqualTo("date");
    assertThat(sortOrders.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);
    assertThat(sortOrders.get(1).getProperty()).isEqualTo("id");
    assertThat(sortOrders.get(1).getDirection()).isEqualTo(Sort.Direction.DESC);
  }

  @Test
  void searchTransactions_unsupportedSortField_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .param("sort", "deletedAt,asc")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("deletedAt")));

    verify(transactionService, never()).search(any(), any(Pageable.class));
  }

  @Test
  void searchTransactions_pageSizeAboveLimit_isCapped() throws Exception {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .param("size", "500")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());

    var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(transactionService).search(any(), pageableCaptor.capture());
    var pageable = pageableCaptor.getValue();
    assertThat(pageable.getPageSize()).isEqualTo(100);
  }

  @Test
  void searchTransactions_returnsPagedResponseContract() throws Exception {
    var transaction = createTransaction(1L, "usr_owner1", "Coffee Shop", BigDecimal.valueOf(4.50));
    var page = new PageImpl<>(List.of(transaction), Pageable.ofSize(50), 1);
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(page);

    mockMvc
        .perform(get("/v1/admin/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.content[0].ownerId").value("usr_owner1"))
        .andExpect(jsonPath("$.content[0].accountId").value("test-account"))
        .andExpect(jsonPath("$.content[0].bankName").value("Test Bank"))
        .andExpect(jsonPath("$.content[0].date").value("2025-10-14"))
        .andExpect(jsonPath("$.content[0].currencyIsoCode").value("USD"))
        .andExpect(jsonPath("$.content[0].amount").value(4.50))
        .andExpect(jsonPath("$.content[0].type").value("DEBIT"))
        .andExpect(jsonPath("$.content[0].description").value("Coffee Shop"))
        .andExpect(jsonPath("$.metadata.page").value(0))
        .andExpect(jsonPath("$.metadata.size").value(50))
        .andExpect(jsonPath("$.metadata.numberOfElements").value(1))
        .andExpect(jsonPath("$.metadata.totalElements").value(1))
        .andExpect(jsonPath("$.metadata.totalPages").value(1))
        .andExpect(jsonPath("$.metadata.first").value(true))
        .andExpect(jsonPath("$.metadata.last").value(true));
  }

  @Test
  void searchTransactions_filterParams_bindCorrectly() throws Exception {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .param("ownerId", "usr_test123")
                .param("bankName", "Capital One")
                .param("type", "DEBIT")
                .param("currencyIsoCode", "USD")
                .param("description", "Coffee")
                .param("dateFrom", "2025-01-01")
                .param("dateTo", "2025-12-31")
                .param("minAmount", "10.00")
                .param("maxAmount", "500.00")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());

    var filterCaptor = ArgumentCaptor.forClass(TransactionFilter.class);
    verify(transactionService).search(filterCaptor.capture(), any(Pageable.class));
    var filter = filterCaptor.getValue();
    assertThat(filter.ownerId()).isEqualTo("usr_test123");
    assertThat(filter.bankName()).isEqualTo("Capital One");
    assertThat(filter.type()).isEqualTo(TransactionType.DEBIT);
    assertThat(filter.currencyIsoCode()).isEqualTo("USD");
    assertThat(filter.description()).isEqualTo("Coffee");
    assertThat(filter.dateFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
    assertThat(filter.dateTo()).isEqualTo(LocalDate.of(2025, 12, 31));
    assertThat(filter.minAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
    assertThat(filter.maxAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
  }

  @Test
  void searchTransactions_timestampFilterParams_bindCorrectly() throws Exception {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc
        .perform(
            get("/v1/admin/transactions")
                .param("createdAfter", "2025-10-14T00:00:00Z")
                .param("createdBefore", "2025-10-15T00:00:00Z")
                .param("updatedAfter", "2025-10-16T12:30:00Z")
                .param("updatedBefore", "2025-10-17T18:45:00Z")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk());

    var filterCaptor = ArgumentCaptor.forClass(TransactionFilter.class);
    verify(transactionService).search(filterCaptor.capture(), any(Pageable.class));
    var filter = filterCaptor.getValue();
    assertThat(filter.createdAfter()).isEqualTo(Instant.parse("2025-10-14T00:00:00Z"));
    assertThat(filter.createdBefore()).isEqualTo(Instant.parse("2025-10-15T00:00:00Z"));
    assertThat(filter.updatedAfter()).isEqualTo(Instant.parse("2025-10-16T12:30:00Z"));
    assertThat(filter.updatedBefore()).isEqualTo(Instant.parse("2025-10-17T18:45:00Z"));
  }

  @Test
  void searchTransactions_ownerIdPresentInEachItem() throws Exception {
    var transactions =
        List.of(
            createTransaction(1L, "usr_owner1", "Groceries", BigDecimal.valueOf(50.00)),
            createTransaction(2L, "usr_owner2", "Gas", BigDecimal.valueOf(35.00)));
    var page = new PageImpl<>(transactions, Pageable.ofSize(50), 2);
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(page);

    mockMvc
        .perform(get("/v1/admin/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].ownerId").value("usr_owner1"))
        .andExpect(jsonPath("$.content[1].ownerId").value("usr_owner2"));
  }

  @Test
  void searchTransactions_emptyResult_returnsEmptyPage() throws Exception {
    when(transactionService.search(any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc
        .perform(get("/v1/admin/transactions").with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.metadata.page").value(0))
        .andExpect(jsonPath("$.metadata.totalElements").value(0))
        .andExpect(jsonPath("$.metadata.totalPages").value(1))
        .andExpect(jsonPath("$.metadata.first").value(true))
        .andExpect(jsonPath("$.metadata.last").value(true));
  }

  @Test
  void countTransactions_returnsCount() throws Exception {
    when(transactionService.countNotDeleted(any())).thenReturn(42L);

    mockMvc
        .perform(
            get("/v1/admin/transactions/count")
                .param("ownerId", "usr_test123")
                .with(ClaimsHeaderTestBuilder.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value(42));

    var filterCaptor = ArgumentCaptor.forClass(TransactionFilter.class);
    verify(transactionService).countNotDeleted(filterCaptor.capture());
    assertThat(filterCaptor.getValue().ownerId()).isEqualTo("usr_test123");
  }

  private Transaction createTransaction(
      Long id, String ownerId, String description, BigDecimal amount) {
    var transaction = new Transaction();
    transaction.setId(id);
    transaction.setOwnerId(ownerId);
    transaction.setAccountId("test-account");
    transaction.setBankName("Test Bank");
    transaction.setDate(LocalDate.of(2025, 10, 14));
    transaction.setCurrencyIsoCode("USD");
    transaction.setAmount(amount);
    transaction.setType(TransactionType.DEBIT);
    transaction.setDescription(description);

    return transaction;
  }
}
