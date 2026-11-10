package com.packvium.commerce.catalog;

/**
 * An as-of time was resolved before any catalog version had become effective.
 */
public final class NoEffectiveVersionException extends CatalogException {
    private static final long serialVersionUID = 1L;

    public NoEffectiveVersionException(String message) {
        super(message);
    }
}
