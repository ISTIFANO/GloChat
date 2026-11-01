package com.marketchat.commerce.catalog.application;

import com.marketchat.commerce.shared.domain.BusinessException;

/** Catalog business error with a stable code (spec §8 error codes), mapped to RFC 7807 by the web layer. */
public class CatalogException extends BusinessException {

    public CatalogException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
    }
}
