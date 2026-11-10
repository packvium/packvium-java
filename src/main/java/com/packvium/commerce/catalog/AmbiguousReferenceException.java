package com.packvium.commerce.catalog;

/**
 * A reference gave neither an explicit version nor an as-of time while more than one
 * version exists, so which version is "the" answer is undefined.
 */
public final class AmbiguousReferenceException extends CatalogException {
    private static final long serialVersionUID = 1L;

    public AmbiguousReferenceException(String message) {
        super(message);
    }
}
