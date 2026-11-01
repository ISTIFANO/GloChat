package com.marketchat.commerce.catalog.application;

/** Business error with a stable code (spec §8 error codes), mapped to RFC 7807 by the web layer. */
public class CatalogException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public CatalogException(String code, int httpStatus, String message) {
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
