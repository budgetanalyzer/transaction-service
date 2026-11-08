package org.budgetanalyzer.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.budgetanalyzer.service.audit.AuditLogger;
import org.budgetanalyzer.service.security.test.ClaimsHeaderTestBuilder;

class RuntimeSecurityIntegrationTest extends ControllerIntegrationTestSupport {

  private static final Set<String> AUTHORIZATION_EXCEPTIONS = Set.of();

  private final ch.qos.logback.classic.Logger auditLogbackLogger =
      (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuditLogger.LOGGER_NAME);
  private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

  @BeforeEach
  void attachAuditAppender() {
    listAppender.start();
    auditLogbackLogger.addAppender(listAppender);
  }

  @AfterEach
  void detachAuditAppender() {
    auditLogbackLogger.detachAppender(listAppender);
    listAppender.stop();
  }

  @Test
  void allApplicationHandlersDeclareAuthorizationPolicy() {
    var applicationHandlers =
        requestMappingHandlerMapping.getHandlerMethods().values().stream()
            .filter(RuntimeSecurityIntegrationTest::isApplicationHandler)
            .toList();
    var handlerIdentifiers =
        applicationHandlers.stream()
            .map(RuntimeSecurityIntegrationTest::handlerIdentifier)
            .collect(java.util.stream.Collectors.toSet());
    var handlersWithoutAuthorization =
        applicationHandlers.stream()
            .filter(
                handlerMethod ->
                    !AUTHORIZATION_EXCEPTIONS.contains(handlerIdentifier(handlerMethod)))
            .filter(handlerMethod -> !hasPreAuthorize(handlerMethod))
            .map(RuntimeSecurityIntegrationTest::handlerIdentifier)
            .sorted()
            .toList();

    assertThat(handlerIdentifiers).containsAll(AUTHORIZATION_EXCEPTIONS);
    assertThat(handlersWithoutAuthorization)
        .as("application request handlers without @PreAuthorize")
        .isEmpty();
  }

  @Test
  void stateChangingRequestEmitsExactlyOneAuditEvent() throws Exception {
    var transaction = persistTransaction(USER_ID, "Audited transaction");

    mockMvc
        .perform(
            delete("/v1/transactions/{id}", transaction.getId())
                .with(ClaimsHeaderTestBuilder.user(USER_ID).withPermissions("transactions:delete")))
        .andExpect(status().isNoContent());

    assertThat(listAppender.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("\"actor\":\"" + USER_ID + "\"")
        .contains("\"operation\":\"DELETE /v1/transactions/{id}\"");
  }

  private static boolean isApplicationHandler(HandlerMethod handlerMethod) {
    return handlerMethod.getBeanType().getPackageName().startsWith("org.budgetanalyzer");
  }

  private static boolean hasPreAuthorize(HandlerMethod handlerMethod) {
    return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class)
            != null
        || AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), PreAuthorize.class)
            != null;
  }

  private static String handlerIdentifier(HandlerMethod handlerMethod) {
    return handlerMethod.getMethod().toGenericString();
  }
}
