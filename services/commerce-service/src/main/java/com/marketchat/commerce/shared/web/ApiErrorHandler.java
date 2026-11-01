package com.marketchat.commerce.shared.web;

import com.marketchat.commerce.catalog.application.CatalogException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 errors with a stable machine-readable "code" (spec §9.1). */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(CatalogException.class)
    ProblemDetail catalog(CatalogException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.httpStatus()), e.getMessage());
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
        pd.setProperty("code", "INVALID_ARGUMENT");
        pd.setProperty("fields", e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
        return pd;
    }
}
