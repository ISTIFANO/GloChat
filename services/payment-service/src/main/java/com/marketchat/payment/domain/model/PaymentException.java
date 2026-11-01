package com.marketchat.payment.domain.model;

/** Business error with a stable code, mapped to an RFC 7807 ProblemDetail by the web layer. */
public class PaymentException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public PaymentException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
