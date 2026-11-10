package com.packvium.serialization;

/**
 * A public request field this engine does not implement yet.
 *
 * <p>Refusing is the whole point: ignoring an unimplemented field returns a confident answer
 * computed as though the caller had never sent it. The message opens with the
 * {@code unsupported_feature} code the conformance corpus matches on, so a staged rollout can
 * assert the rejection rather than merely tolerate it.
 */
public final class UnsupportedFeatureException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public UnsupportedFeatureException(String message) {
        super(message);
    }
}
