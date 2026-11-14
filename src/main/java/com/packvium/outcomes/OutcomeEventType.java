package com.packvium.outcomes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one outcome event records, and the closed payload vocabulary each kind accepts.
 *
 * <p>Deliberately narrow and structured -- ids, numeric measurements, enumerated reason codes.
 * Nothing here is a free-text field a caller could use to smuggle in personally identifiable data.
 */
public enum OutcomeEventType {
    ALTERNATIVE_CHOSEN("alternative_chosen", "alternative_index", "objective_score"),
    ACTUAL_CARTON("actual_carton", "carton_id", "catalog_version"),
    MEASURED_DIMENSIONS("measured_dimensions", "length_mm", "width_mm", "height_mm"),
    MEASURED_WEIGHT("measured_weight", "weight_g"),
    REPACK("repack", "reason_code", "new_carton_id"),
    DAMAGE("damage", "reason_code", "severity"),
    RETURN("return", "reason_code"),
    OPERATOR_OVERRIDE("operator_override", "reason_code", "operator_id");

    private static final List<OutcomeEventType> ALL = List.of(values());

    private final String value;
    private final Set<String> allowedFields;

    OutcomeEventType(String value, String... allowedFields) {
        this.value = value;
        this.allowedFields = Set.of(allowedFields);
    }

    /** The wire spelling, such as {@code actual_carton}. */
    public String value() {
        return value;
    }

    /** The only payload fields an event of this kind may carry. Unordered; sort before printing. */
    public Set<String> allowedFields() {
        return allowedFields;
    }

    /** The kind spelt {@code value}, if there is one. */
    public static Optional<OutcomeEventType> tryFrom(String value) {
        return ALL.stream().filter(type -> type.value.equals(value)).findFirst();
    }
}
