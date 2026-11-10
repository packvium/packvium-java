package com.packvium.commerce.rating;

/**
 * No tariff is registered under the given carrier/service id, or no version of it is
 * effective at the requested time / exists at the requested version number.
 */
public final class TariffNotFoundException extends RatingException {
    private static final long serialVersionUID = 1L;

    public TariffNotFoundException(String message) {
        super(message);
    }
}
