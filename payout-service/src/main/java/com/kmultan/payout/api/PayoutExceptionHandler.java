package com.kmultan.payout.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.kmultan.platform.web.ProblemDetails;

@RestControllerAdvice
public class PayoutExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail notFound(IllegalArgumentException exception) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, "Not found", exception.getMessage());
    }
}
