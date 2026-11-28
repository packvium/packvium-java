package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The objective vector recomputed from the result alone, and the request-level rules only the
 * whole result can answer: fixed placements, groups and solver selection.
 */
final class ObjectiveCheck {
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

    private ObjectiveCheck() {
    }

    /** How a billed objective prices a container, as the request configures it. */
    private record Billing(Object divisor, Object lengthUnit, Object weightUnit) {
    }

    static void check(Object request, Object result, Context context, Ordered<ContainerSpec> fabricated,
            List<ResultIssue> issues) {
        Object configuration = Json.get(request, "configuration", Map.of());
        Object selected = Json.get(configuration, "objective", "default");
        Object reportedName = Json.get(result, "objective");
        if ((Json.has(configuration, "objective") || reportedName != null) && !Json.equals(reportedName, selected)) {
            issues.add(new ResultIssue("objective_mismatch", "reported " + PyFormat.repr(reportedName) + ", requested "
                    + PyFormat.repr(selected)));
        }
        List<BigInteger> reported = new ArrayList<>();
        for (Object component : Json.items(Json.at(result, "score"), "score")) {
            reported.add(Json.pyInt(component, "score"));
        }
        Billing billing = new Billing(Json.get(configuration, "dimensional_weight_divisor"),
                Json.get(configuration, "dimensional_weight_length_unit", "in"),
                Json.get(configuration, "dimensional_weight_weight_unit", "lb"));
        List<BigInteger> recomputed = objective(result, context, fabricated, selected, billing);
        if (!reported.equals(recomputed)) {
            issues.add(new ResultIssue("score_mismatch", "reported " + PyFormat.intList(reported) + ", recomputed "
                    + PyFormat.intList(recomputed)));
        }
    }

    private static List<BigInteger> objective(Object result, Context context, Ordered<ContainerSpec> fabricated,
            Object name, Billing billing) {
        BigInteger cost = BigInteger.ZERO;
        BigInteger unused = BigInteger.ZERO;
        BigInteger height = BigInteger.ZERO;
        BigInteger billable = BigInteger.ZERO;
        BigInteger achievedHeight = BigInteger.ZERO;
        BigInteger landed = BigInteger.ZERO;
        boolean billed = Json.isText(name, "shipping_cost") || Json.isText(name, "lowest_landed_cost");
        List<?> containers = Json.items(Json.at(result, "containers"), "result.containers");
        for (Object packed : containers) {
            ContainerSpec spec = fabricated.get(Json.get(packed, "id"));
            if (spec == null) {
                spec = context.containers().get(Json.at(packed, "container_type"));
            }
            if (spec == null) {
                continue;
            }
            cost = cost.add(spec.costMinor());
            BigInteger volume = spec.volume();
            if (volume.signum() == 0) {
                continue;
            }
            BigInteger used = Json.pyInt(Json.at(packed, "used_volume_ticks3"), "used_volume_ticks3");
            unused = unused.add(Exact.floorDiv(volume.subtract(used).multiply(ContainerCheck.SCORE_SCALE), volume));
            BigInteger top = null;
            for (Object placement : Json.items(Json.at(packed, "placements"), "placements")) {
                BigInteger z = Json.integer(Json.at(Json.at(Json.at(placement, "position"), "z"), "ticks"), "placement ticks");
                BigInteger extent = Json.integer(Json.at(Json.at(Json.at(placement, "dimensions"), "height"), "ticks"),
                        "placement ticks");
                BigInteger reach = z.add(extent).add(context.clearance());
                top = top == null ? reach : top.max(reach);
            }
            top = top == null ? BigInteger.ZERO : top;
            height = height.add(Exact.floorDiv(top.multiply(ContainerCheck.SCORE_SCALE), spec.inner().get(2)));
            achievedHeight = achievedHeight.add(top);
            if (billed && billing.divisor() != null) {
                BigInteger gross = Json.pyInt(Json.get(Json.get(packed, "gross_weight", Map.of()), "ticks", 0L), "gross_weight");
                List<BigInteger> size = spec.flatBag() ? ContainerCheck.achievedExtent(packed, context.clearance())
                        : spec.outer() != null ? spec.outer() : spec.inner();
                BigInteger dimensional = dimensionalWeight(size, Json.integer(billing.divisor(), "dimensional_weight_divisor"),
                        Json.text(billing.lengthUnit(), "dimensional_weight_length_unit"),
                        Json.text(billing.weightUnit(), "dimensional_weight_weight_unit"));
                BigInteger charged = gross.max(dimensional);
                billable = billable.add(charged);
                if (Json.isText(name, "lowest_landed_cost")) {
                    landed = landed.add(landedCharge(spec, charged));
                }
            }
        }
        List<?> unpackedItems = Json.items(Json.at(result, "unpacked_items"), "result.unpacked_items");
        BigInteger unpacked = BigInteger.valueOf(unpackedItems.size());
        BigInteger opened = BigInteger.valueOf(containers.size());
        if (!(name instanceof String objective)) {
            throw MalformedResult.contract("unknown requested objective " + PyFormat.repr(name));
        }
        return switch (objective) {
            case "default" -> List.of(unpacked, opened, cost, unused, height);
            case "lowest_cost" -> List.of(unpacked, cost, opened, unused, height);
            case "shipping_cost" -> {
                requireDivisor(billing, objective);
                yield List.of(unpacked, billable, opened, unused, height);
            }
            case "lowest_landed_cost" -> {
                requireDivisor(billing, objective);
                yield List.of(unpacked, landed, opened, unused, height);
            }
            case "open_dimension_height" -> List.of(unpacked, achievedHeight, opened, cost, unused);
            case "maximum_value" -> List.of(unpacked, valueForgone(unpackedItems, context), opened, cost, unused);
            case "maximum_volume" -> List.of(unpackedVolumePpm(unpackedItems, context), unpacked, opened, cost, unused);
            default -> throw MalformedResult.contract("unknown requested objective " + PyFormat.repr(name));
        };
    }

    private static void requireDivisor(Billing billing, String objective) {
        if (billing.divisor() == null) {
            throw MalformedResult.contract(objective + " objective requires configuration.dimensional_weight_divisor");
        }
    }

    private static BigInteger valueForgone(List<?> unpackedItems, Context context) {
        BigInteger forgone = BigInteger.ZERO;
        for (Object item : unpackedItems) {
            ItemSpec spec = context.items().get(Json.at(item, "item_type"));
            if (spec != null && Json.truthy(spec.value())) {
                forgone = forgone.add(Json.integer(spec.value(), "value"));
            }
        }
        return forgone;
    }

    /** The share of the requested volume left unpacked, rounded up so an incomplete answer never scores zero. */
    private static BigInteger unpackedVolumePpm(List<?> unpackedItems, Context context) {
        BigInteger unpackedVolume = BigInteger.ZERO;
        for (Object item : unpackedItems) {
            ItemSpec spec = context.items().get(Json.at(item, "item_type"));
            if (spec != null) {
                unpackedVolume = unpackedVolume.add(nominal(spec));
            }
        }
        BigInteger requested = BigInteger.ZERO;
        for (ItemSpec spec : context.items().values()) {
            requested = requested.add(nominal(spec).multiply(Json.integer(spec.quantity(), "quantity")));
        }
        return requested.signum() == 0 ? BigInteger.ZERO
                : Exact.ceilDiv(unpackedVolume.multiply(ContainerCheck.SCORE_SCALE), requested);
    }

    private static BigInteger nominal(ItemSpec spec) {
        return spec.dimensions().get(0).multiply(spec.dimensions().get(1)).multiply(spec.dimensions().get(2));
    }

    /** {@code floor(volume * weight ticks per unit / (length ticks per unit ^ 3 * divisor))}. */
    private static BigInteger dimensionalWeight(List<BigInteger> size, BigInteger divisor, String lengthUnit,
            String weightUnit) {
        BigInteger length = Units.perUnit(Units.LENGTH, lengthUnit);
        BigInteger weight = Units.perUnit(Units.WEIGHT, weightUnit);
        BigInteger volume = size.get(0).multiply(size.get(1)).multiply(size.get(2));
        return Exact.floorDiv(volume.multiply(weight), length.pow(3).multiply(divisor));
    }

    /**
     * One container's carrier charge from the request's own rate card: billed weight up to whole
     * grams, the minimum charge, then the fuel surcharge rounded up.
     */
    private static BigInteger landedCharge(ContainerSpec spec, BigInteger billedTicks) {
        if (spec.rateTable() == null) {
            throw MalformedResult.contract("container " + PyFormat.repr(spec.id())
                    + " has no rate_table, so lowest_landed_cost cannot price it");
        }
        Object table = spec.rateTable();
        List<BigInteger> brackets = new ArrayList<>();
        for (Object bound : Json.items(Json.at(table, "weight_brackets_g"), "weight_brackets_g")) {
            brackets.add(Json.pyInt(bound, "bracket"));
        }
        List<BigInteger> prices = new ArrayList<>();
        for (Object price : Json.items(Json.at(table, "prices_minor"), "prices_minor")) {
            prices.add(Json.pyInt(price, "price"));
        }
        BigInteger grams = Exact.ceilDiv(billedTicks, Units.TICKS_PER_GRAM);
        for (int index = 0; index < brackets.size() && index < prices.size(); index++) {
            if (grams.compareTo(brackets.get(index)) <= 0) {
                BigInteger base = prices.get(index).max(Json.pyInt(Json.get(table, "minimum_charge_minor", 0L),
                        "minimum_charge_minor"));
                BigInteger permille = Json.pyInt(Json.get(table, "fuel_surcharge_permille", 0L), "fuel_surcharge_permille");
                return base.add(Exact.ceilDiv(base.multiply(permille), THOUSAND));
            }
        }
        if (brackets.isEmpty()) {
            throw MalformedResult.shape("an empty rate table");
        }
        throw MalformedResult.contract("container " + PyFormat.repr(spec.id()) + " bills at " + grams
                + " g, above its rate table's last bracket (" + brackets.get(brackets.size() - 1)
                + " g); the shipment has no published price");
    }

    static void checkSolverSelection(Object request, Object result, List<ResultIssue> issues) {
        Object selected = Json.get(Json.get(request, "configuration", Map.of()), "solvers", List.of());
        if (!Json.truthy(selected)) {
            return;
        }
        List<?> listed = Json.items(selected, "configuration.solvers");
        Object reported = Json.get(Json.at(result, "algorithm"), "solver");
        Object base = reported instanceof String text ? text.split(":", 2)[0] : reported;
        if (listed.stream().anyMatch(solver -> Json.equals(solver, base))) {
            return;
        }
        issues.add(new ResultIssue("solver_selection_mismatch", "reported " + PyFormat.repr(reported)
                + ", requested one of " + PyFormat.repr(selected)));
    }

    /** A group travels together or not at all: one container, and never half-packed. */
    static void checkGroups(Object request, Object result, List<ResultIssue> issues) {
        List<?> rawItems = Json.items(Json.at(request, "items"), "request.items");
        Ordered<Object> groups = new Ordered<>();
        for (Object raw : rawItems) {
            if (Json.get(raw, "group") != null) {
                groups.set(Json.at(raw, "id"), Json.at(raw, "group"));
            }
        }
        if (groups.size() == 0) {
            return;
        }
        Ordered<Ordered<Boolean>> located = new Ordered<>();
        Ordered<BigInteger> packed = new Ordered<>();
        for (Object container : Json.items(Json.at(result, "containers"), "result.containers")) {
            for (Object placement : Json.items(Json.at(container, "placements"), "placements")) {
                Object group = groups.get(Json.at(placement, "item_type"));
                if (group == null) {
                    continue;
                }
                Ordered<Boolean> places = located.get(group, new Ordered<>());
                places.set(Json.at(container, "id"), true);
                located.set(group, places);
                packed.set(group, packed.get(group, BigInteger.ZERO).add(BigInteger.ONE));
            }
        }
        Ordered<BigInteger> expected = new Ordered<>();
        for (Object raw : rawItems) {
            Object group = Json.get(raw, "group");
            if (group != null) {
                expected.set(group, expected.get(group, BigInteger.ZERO)
                        .add(Json.integer(Json.get(raw, "quantity", 1L), "quantity")));
            }
        }
        for (Object group : Json.sorted(located.keys())) {
            Ordered<Boolean> places = located.get(group);
            if (places.size() > 1) {
                List<String> where = new ArrayList<>();
                places.keys().forEach(place -> where.add(Json.text(place, "a container id")));
                issues.add(new ResultIssue("group_split", PyFormat.str(group) + ": " + String.join(", ", Json.sorted(where))));
            }
            BigInteger placed = packed.get(group, BigInteger.ZERO);
            BigInteger wanted = expected.get(group, BigInteger.ZERO);
            if (!placed.equals(wanted)) {
                issues.add(new ResultIssue("group_partially_packed", PyFormat.str(group) + ": " + placed + " of " + wanted
                        + " placed"));
            }
        }
    }

    /** Every fixed placement is reported where the request put it, and nothing else claims to be. */
    static void checkFixedPlacements(Object request, Object result, String unit, List<ResultIssue> issues) {
        Map<List<Object>, List<Object>> requested = new LinkedHashMap<>();
        for (Object entry : Json.items(Json.get(request, "fixed_placements", List.of()), "fixed_placements")) {
            String containerId = PyFormat.str(Json.at(entry, "container_type")) + "#"
                    + PyFormat.str(Json.get(entry, "container_instance", 1L));
            Object position = Json.get(entry, "position", Map.of());
            List<Object> key = new ArrayList<>(List.of(containerId, Json.at(entry, "item_type")));
            for (String axis : List.of("x", "y", "z")) {
                key.add(Units.toTicks(Json.get(position, axis, 0L), unit, Units.LENGTH));
            }
            key.add(Json.at(entry, "orientation"));
            requested.put(hashed(key), key);
        }
        Map<List<Object>, List<Object>> reported = new LinkedHashMap<>();
        Ordered<Object> containerTypes = new Ordered<>();
        for (Object container : Json.items(Json.at(result, "containers"), "result.containers")) {
            Object containerId = Json.get(container, "id", "");
            containerTypes.set(containerId, Json.at(container, "container_type"));
            for (Object placement : Json.items(Json.at(container, "placements"), "placements")) {
                if (!Boolean.TRUE.equals(Json.get(placement, "fixed"))) {
                    continue;
                }
                Object position = Json.at(placement, "position");
                List<Object> key = new ArrayList<>(List.of(containerId, Json.at(placement, "item_type")));
                for (String axis : List.of("x", "y", "z")) {
                    key.add(Json.at(Json.at(position, axis), "ticks"));
                }
                key.add(Json.at(placement, "orientation"));
                reported.put(hashed(key), key);
            }
        }
        for (List<Object> key : Json.sorted(new ArrayList<>(requested.values()))) {
            String containerId = (String) key.get(0);
            int hash = containerId.lastIndexOf('#');
            String declaredType = hash < 0 ? containerId : containerId.substring(0, hash);
            if (!Json.isText(containerTypes.get(containerId), declaredType)) {
                issues.add(new ResultIssue("fixed_container_missing", containerId));
            } else if (!reported.containsKey(hashed(key))) {
                issues.add(new ResultIssue("fixed_placement_moved", PyFormat.str(key.get(1)) + " fixed in " + containerId
                        + " at " + PyFormat.valueTuple(key.subList(2, 5)) + " " + PyFormat.str(key.get(5))));
            }
        }
        List<List<Object>> unexpected = new ArrayList<>();
        reported.forEach((hash, key) -> {
            if (!requested.containsKey(hash)) {
                unexpected.add(key);
            }
        });
        for (List<Object> key : Json.sorted(unexpected)) {
            issues.add(new ResultIssue("unexpected_fixed_placement", PyFormat.str(key.get(1)) + " in "
                    + PyFormat.str(key.get(0)) + " at " + PyFormat.valueTuple(key.subList(2, 5)) + " "
                    + PyFormat.str(key.get(5))));
        }
    }

    private static List<Object> hashed(List<Object> key) {
        return key.stream().map(Json::hashKey).toList();
    }
}
