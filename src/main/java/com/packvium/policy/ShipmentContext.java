package com.packvium.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Declared facts about the shipment, or the absence of one.
 *
 * <p>A fact nobody declared is not a wildcard: a rule naming it simply never participates, so a
 * rule written for one customer cannot silently apply to a shipment that named no customer at
 * all.
 */
public record ShipmentContext(
        Optional<String> facility,
        Optional<String> customer,
        Optional<String> carrier,
        Optional<String> service) {

    /**
     * Facts a rule may select on. Properties of the shipment rather than of any item or
     * container, which is why the request had nowhere to put them before.
     */
    public static final List<String> FACTS = List.of("facility", "customer", "carrier", "service");

    public ShipmentContext() {
        this(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static ShipmentContext fromArray(Object raw, String where) {
        if (raw == null) {
            return new ShipmentContext();
        }
        if (!PhpValues.isArray(raw)) {
            throw new PolicyException(where + " must be an object");
        }
        List<String> unknown = PhpValues.sorted(
                PhpValues.keys(raw).stream().filter(key -> !FACTS.contains(key)).toList());
        if (!unknown.isEmpty()) {
            throw new PolicyException(where + " names unknown shipment facts: " + String.join(", ", unknown));
        }
        // Every key is now a fact name, so a list (whose keys are indices) is necessarily empty.
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> fact : map.entrySet()) {
                if (!(fact.getValue() instanceof String value) || value.isEmpty()) {
                    throw new PolicyException(where + "." + fact.getKey() + " must be a non-empty string");
                }
            }
        }
        return new ShipmentContext(fact(raw, "facility"), fact(raw, "customer"), fact(raw, "carrier"),
                fact(raw, "service"));
    }

    /** Whether every fact this selector names equals the shipment's own. O(1). */
    public boolean satisfiedBy(ShipmentContext shipment) {
        return matches(facility, shipment.facility) && matches(customer, shipment.customer)
                && matches(carrier, shipment.carrier) && matches(service, shipment.service);
    }

    private static boolean matches(Optional<String> selector, Optional<String> actual) {
        return selector.isEmpty() || selector.equals(actual);
    }

    private static Optional<String> fact(Object raw, String name) {
        return Optional.ofNullable((String) PhpValues.get(raw, name));
    }
}
