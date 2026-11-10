package com.packvium.commerce.rating;

import java.util.HashSet;
import java.util.List;

/**
 * What is being rated: a shipment's real weight and volume, the zone it is moving to,
 * and whichever accessorial services this specific shipment needs.
 */
public record RatingRequest(String zone, long actualWeightG, long volumeMm3, List<String> requestedAccessorials) {
    public RatingRequest {
        if (zone.isEmpty()) {
            throw new IllegalArgumentException("zone is required");
        }
        if (actualWeightG < 0) {
            throw new IllegalArgumentException("actual_weight_g cannot be negative");
        }
        if (volumeMm3 < 0) {
            throw new IllegalArgumentException("volume_mm3 cannot be negative");
        }
        requestedAccessorials = List.copyOf(requestedAccessorials);
        for (String accessorial : requestedAccessorials) {
            if (accessorial.isEmpty()) {
                throw new IllegalArgumentException("requested accessorial ids must be non-empty");
            }
        }
        if (new HashSet<>(requestedAccessorials).size() != requestedAccessorials.size()) {
            throw new IllegalArgumentException("requested accessorial ids must be unique");
        }
    }

    public RatingRequest(String zone, long actualWeightG, long volumeMm3) {
        this(zone, actualWeightG, volumeMm3, List.of());
    }
}
