package com.packvium.commerce.catalog;

/** A facility-specific override of a base master-data entry. */
public record FacilityOverride(String id, String facilityId, String entryId, MasterEntry override) {
    public FacilityOverride {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("facility override id is required");
        }
        if (facilityId.isEmpty()) {
            throw new IllegalArgumentException("facility_id is required");
        }
        if (!override.id().equals(entryId)) {
            throw new IllegalArgumentException("a facility override's entry_id must match override.id");
        }
    }
}
