package com.packvium.domain;

/** The billed weight lies above a rate table's last bracket, so the shipment has no price. */
public final class UnratedWeightException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnratedWeightException(String message) {
        super(message);
    }
}
