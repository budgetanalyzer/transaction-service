package org.budgetanalyzer.transaction.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Enables method-level security for @WebMvcTest authorization tests. */
@TestConfiguration
@EnableMethodSecurity
class MethodSecurityTestConfig {}
