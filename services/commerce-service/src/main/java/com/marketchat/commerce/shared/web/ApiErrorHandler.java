package com.marketchat.commerce.shared.web;

import com.marketchat.commerce.shared.domain.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 errors with a stable machine-readable "code" (spec §9.1). */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(BusinessException.class)
    ProblemDetail business(BusinessException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.httpStatus()), e.getMessage());
        pd.setProperty("code", e.code());
        e.details().forEach(pd::setProperty);
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON body");
        pd.setProperty("code", "INVALID_ARGUMENT");
        return pd;
    }
}
