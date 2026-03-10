package org.budgetanalyzer.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import org.budgetanalyzer.service.security.test.TestClaimsSecurityConfig;

@SpringBootTest
@Import(TestClaimsSecurityConfig.class)
class TransactionServiceApplicationTests {

  @Test
  void contextLoads() {}
}
