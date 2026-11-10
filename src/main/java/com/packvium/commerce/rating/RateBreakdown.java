package com.packvium.commerce.rating;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fully itemized, auditable result of one rating call. Every component that
 * contributed to totalMinor is named individually, alongside the exact tariff version
 * that produced it.
 *
 * @param accessorialChargesMinor (accessorial id, charge) in requested order
 */
public record RateBreakdown(String carrierId, String serviceId, long tariffVersion, String zone, long actualWeightG,
        long dimensionalWeightG, long billedWeightG, long baseChargeMinor, boolean minimumChargeApplied,
        long fuelSurchargeMinor, List<Map.Entry<String, Long>> accessorialChargesMinor, long totalMinor) {
    public RateBreakdown {
        accessorialChargesMinor = List.copyOf(accessorialChargesMinor);
    }

    /** The wire shape docs/COMMERCE-API.md specifies. */
    public Map<String, Object> toArray() {
        List<Object> charges = new ArrayList<>(accessorialChargesMinor.size());
        for (Map.Entry<String, Long> charge : accessorialChargesMinor) {
            charges.add(List.of(charge.getKey(), charge.getValue()));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("carrier_id", carrierId);
        fields.put("service_id", serviceId);
        fields.put("tariff_version", tariffVersion);
        fields.put("zone", zone);
        fields.put("actual_weight_g", actualWeightG);
        fields.put("dimensional_weight_g", dimensionalWeightG);
        fields.put("billed_weight_g", billedWeightG);
        fields.put("base_charge_minor", baseChargeMinor);
        fields.put("minimum_charge_applied", minimumChargeApplied);
        fields.put("fuel_surcharge_minor", fuelSurchargeMinor);
        fields.put("accessorial_charges_minor", charges);
        fields.put("total_minor", totalMinor);
        return fields;
    }
}
