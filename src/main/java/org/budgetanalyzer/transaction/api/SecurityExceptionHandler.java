package org.budgetanalyzer.transaction.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.budgetanalyzer.service.api.ApiErrorResponse;
import org.budgetanalyzer.service.api.ApiErrorType;

/**
 * Handles Spring Security access-denied exceptions, returning 403 instead of propagating to the
 * generic 500 handler in service-common's ServletApiExceptionHandler.
 *
 * <p>This is needed because {@code @PreAuthorize} failures throw {@link AccessDeniedException} from
 * within the controller AOP proxy, which MVC exception resolvers handle before the exception
 * reaches the security filter chain.
 */
@RestControllerAdvice
public class SecurityExceptionHandler {

  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ApiErrorResponse handleAccessDenied(AccessDeniedException ex) {
    return ApiErrorResponse.builder()
        .type(ApiErrorType.PERMISSION_DENIED)
        .message("Access denied")
        .build();
  }
}
