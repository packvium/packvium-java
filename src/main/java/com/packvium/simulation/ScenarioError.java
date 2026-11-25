package com.packvium.simulation;

/** Base class for every scenario-domain error. */
public class ScenarioError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @param message the reason, worded as the Python engine words it */
    public ScenarioError(String message) {
        super(message);
    }
}
