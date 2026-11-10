package com.packvium.commerce.catalog;

/** Base class for every catalog-domain failure. */
public class CatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CatalogException(String message) {
        super(message);
    }
}
