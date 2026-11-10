package com.packvium.commerce.rating;

import com.packvium.support.PhpArrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable, numbered version of one (carrier_id, service_id) pair's rate card.
 * Append-only and effective-dated, never mutated in place.
 *
 * @param costPerDimensionalKgMinor zone -> minor cost per 1000 g, in document order
 * @param accessorials accessorial id -> charge, in document order
 */
public record Tariff(String carrierId, String serviceId, long version, long effectiveAt, long dimensionalWeightDivisor,
        Map<String, Long> costPerDimensionalKgMinor, long minimumChargeMinor, long fuelSurchargePermille,
        Map<String, AccessorialCharge> accessorials) {
    public Tariff {
        if (carrierId.isEmpty()) {
            throw new IllegalArgumentException("carrier_id is required");
        }
        if (serviceId.isEmpty()) {
            throw new IllegalArgumentException("service_id is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (effectiveAt < 0) {
            throw new IllegalArgumentException("effective_at cannot be negative");
        }
        if (dimensionalWeightDivisor <= 0) {
            throw new IllegalArgumentException("dimensional_weight_divisor must be positive");
        }
        if (minimumChargeMinor < 0) {
            throw new IllegalArgumentException("minimum_charge_minor cannot be negative");
        }
        if (fuelSurchargePermille < 0) {
            throw new IllegalArgumentException("fuel_surcharge_permille cannot be negative");
        }
        costPerDimensionalKgMinor = Collections.unmodifiableMap(new LinkedHashMap<>(costPerDimensionalKgMinor));
        for (long cost : costPerDimensionalKgMinor.values()) {
            if (cost < 0) {
                throw new IllegalArgumentException("cost_per_dimensional_kg_minor entries cannot be negative");
            }
        }
        accessorials = Collections.unmodifiableMap(new LinkedHashMap<>(accessorials));
        for (Map.Entry<String, AccessorialCharge> entry : accessorials.entrySet()) {
            // PHP stores a canonical-integer key such as "5" as the integer 5, which then
            // never strictly equals the string id, so the reference refuses such an id.
            // Reproduced so the two ports refuse the same documents.
            if (PhpArrays.isIntegerKey(entry.getKey()) || !entry.getKey().equals(entry.getValue().accessorialId())) {
                throw new IllegalArgumentException(
                        "accessorials key must match its own accessorial_id: " + entry.getKey());
            }
        }
    }
}
