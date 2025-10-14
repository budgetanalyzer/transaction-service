package com.bleurubin.budgetanalyzer.api;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @ExceptionHandler
    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handle(Exception exception) {
        return handleApiException(exception);
    }

    private ApiErrorResponse handleApiException(Throwable throwable) {
        var message = throwable.getMessage();
        var rootCause = ExceptionUtils.getRootCause(throwable);
        if (rootCause != null) {
            log.warn("Handled exception: {} root cause: {} message: {}", throwable.getClass(), rootCause.getClass(), message, throwable);
        } else {
            log.warn("Handled exception: {} message: {}", throwable.getClass(), message, throwable);
        }

        var rv = new ApiErrorResponse();
        rv.setMessage(message);

        return rv;
    }
}
