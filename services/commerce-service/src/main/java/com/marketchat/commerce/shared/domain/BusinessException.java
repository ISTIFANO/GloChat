package com.marketchat.commerce.shared.domain;

import java.util.Map;

/**
 * Business rule violation with a stable machine-readable code (spec §8 error codes).
 * The web layer maps it to an RFC 7807 ProblemDetail; {@code details} become extra properties.
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final Map<String, Object> details;

    public BusinessException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, Map.of());
    }

    public BusinessException(String code, int httpStatus, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public Map<String, Object> details() {
        return details;
    }
}
