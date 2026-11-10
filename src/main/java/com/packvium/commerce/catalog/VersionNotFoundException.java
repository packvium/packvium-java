package com.packvium.commerce.catalog;

/**
 * An explicitly referenced catalog version number does not exist in the history.
 */
public final class VersionNotFoundException extends CatalogException {
    private static final long serialVersionUID = 1L;

    public VersionNotFoundException(String message) {
        super(message);
    }
}
