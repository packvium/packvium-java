package com.packvium.commerce.rating;

import java.util.List;
import java.util.Optional;

/**
 * The requested zone or accessorial is not defined by the resolved tariff -- a
 * structured rejection naming exactly what was missing, never a silently-zero charge.
 *
 * <p>Exactly one of zone / accessorialIds is set, so the API boundary reads the machine-
 * readable reason off the exception instead of parsing it back out of the message.
 */
public final class UnavailableServiceException extends RatingException {
    private static final long serialVersionUID = 1L;

    // Plain serializable field types; the accessors give the Optional/List view.
    private final String zone;
    private final String[] accessorialIds;

    private UnavailableServiceException(String message, String zone, List<String> accessorialIds) {
        super(message);
        this.zone = zone;
        this.accessorialIds = accessorialIds.toArray(new String[0]);
    }

    public static UnavailableServiceException forZone(String message, String zone) {
        return new UnavailableServiceException(message, zone, List.of());
    }

    public static UnavailableServiceException forAccessorials(String message, List<String> accessorialIds) {
        return new UnavailableServiceException(message, null, accessorialIds);
    }

    public Optional<String> zone() {
        return Optional.ofNullable(zone);
    }

    public List<String> accessorialIds() {
        return List.of(accessorialIds);
    }
}
