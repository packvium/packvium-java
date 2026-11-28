package com.packvium.support;

import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.domain.Container;
import com.packvium.domain.FixedPlacement;
import com.packvium.domain.FlatBag;
import com.packvium.domain.Item;
import com.packvium.domain.MadeToMeasure;
import com.packvium.domain.RateTable;
import com.packvium.domain.Sides;
import com.packvium.serialization.InvalidRequestException;
import com.packvium.validation.FixedPlacementException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The request schema's rules, applied to a request built from objects.
 *
 * <p>{@link RequestRules} walks a JSON request; {@link com.packvium.Packer#pack} takes objects,
 * whose own constructors already refuse most of the same values but cannot know where in a request
 * they will sit. This walks the objects in the JSON rules' order -- configuration, items, item ids,
 * containers, container ids, fixed placements -- with the same primitives, so a value the JSON path
 * refuses is refused here with the same reason and the same RFC 6901 field. Only rules
 * {@link RequestRules} also applies are checked, so on the JSON path, which runs those first, this
 * never refuses anything.
 *
 * <p>An id and a closed vocabulary need no check here: the models hold a {@code String} and an
 * enum. Unordered sets (accepted stacking groups, tag limits) are walked in code-point order, so
 * insertion order never decides which value an error names. O(size of the request), plus
 * O(t log t) to sort t names of one set.
 */
public final class RequestObjects {
    private RequestObjects() {
    }

    /** Refuse the request with its first violation, or return. */
    public static void check(List<Item> items, List<Container> containers, PackingConfig config,
            List<FixedPlacement> fixedPlacements) {
        configuration(config);
        for (int index = 0; index < items.size(); index++) {
            item(items.get(index), "/items/" + index);
        }
        RequestRules.requireUniqueIds(items.stream().map(Item::id).toList(), "items");
        for (int index = 0; index < containers.size(); index++) {
            container(containers.get(index), "/containers/" + index);
        }
        RequestRules.requireUniqueIds(containers.stream().map(Container::id).toList(), "containers");
        for (int index = 0; index < fixedPlacements.size(); index++) {
            fixedPlacement(fixedPlacements.get(index), "fixed_placements[" + index + "]", "/fixed_placements/" + index);
        }
    }

    /**
     * {@code objective} is not checked here: an unknown one keeps its own, older error on this
     * path, {@link com.packvium.objective.UnknownObjectiveException}, thrown when it is resolved.
     */
    private static void configuration(PackingConfig config) {
        String where = "/configuration";
        Map<String, Optional<Long>> integers = new LinkedHashMap<>();
        integers.put("time_limit_ms", Optional.of(config.timeLimitMs()));
        integers.put("alternatives", Optional.of(config.topK()));
        integers.put("max_containers", config.maxContainers());
        integers.put("exact_item_limit", Optional.of(config.exactItemLimit()));
        integers.put("multi_start_orders", Optional.of(config.multiStartOrders()));
        integers.put("max_candidates_per_item", Optional.of(config.maxCandidatesPerItem()));
        integers.put("max_candidate_points", Optional.of(config.maxCandidatePoints()));
        integers.put("container_plan_beam_width", Optional.of(config.containerPlanBeamWidth()));
        integers.put("container_plan_node_limit", Optional.of(config.containerPlanNodeLimit()));
        integers.put("dimensional_weight_divisor", config.dimensionalWeightDivisor());
        for (Map.Entry<String, Long> rule : RequestRules.CONFIGURATION_INTEGERS) {
            optionalInteger(integers.get(rule.getKey()), where + "/" + rule.getKey(), rule.getValue());
        }
        RequestRules.ratio(config.minimumSupportRatio(), where + "/minimum_support_ratio", 1L);
        if (config.effortBudget().isEmpty()) {
            return;
        }
        EffortBudget budget = config.effortBudget().get();
        List<Optional<Long>> limits = List.of(budget.maxCandidatesEvaluated(), budget.maxPlacementAttempts(),
                budget.maxSearchNodes(), budget.maxRestarts());
        for (int index = 0; index < limits.size(); index++) {
            optionalInteger(limits.get(index), where + "/effort_budget/" + RequestRules.EFFORT_LIMITS.get(index), 1);
        }
    }

    private static void item(Item item, String where) {
        RequestRules.integer(item.quantity(), where + "/quantity", 1);
        optionalInteger(item.maxStackedItems(), where + "/max_stacked_items", 1);
        optionalInteger(item.stopIndex(), where + "/stop_index", 0);
        optionalInteger(item.value(), where + "/value", 0);
        optionalInteger(item.maxCompressionPressureKpa(), where + "/max_compression_pressure_kpa", 0);
        RequestRules.ratio(item.minimumSupportRatio(), where + "/minimum_support_ratio", 1L);
        if (item.stackingGroup().isPresent()) {
            RequestRules.stackingGroupName(item.stackingGroup().get(), where + "/stacking_group");
        }
        List<String> accepted = codePointOrder(item.acceptsStackingGroups());
        for (int index = 0; index < accepted.size(); index++) {
            RequestRules.stackingGroupName(accepted.get(index), where + "/accepts_stacking_groups/" + index);
        }
    }

    private static void container(Container container, String where) {
        optionalInteger(container.quantity(), where + "/quantity", 1);
        optionalInteger(container.maxItems(), where + "/max_items", 1);
        RequestRules.integer(container.costMinor(), where + "/cost_minor", 0);
        RequestRules.ratio(container.voidFillReserveRatio(), where + "/void_fill_reserve_ratio", 1L);
        for (String tag : codePointOrder(container.tagLimits().keySet())) {
            RequestRules.integer(container.tagLimits().get(tag), RequestRules.pointer(where + "/tag_limits", tag), 1);
        }
        if (container.rateTable().isPresent()) {
            rateTable(container.rateTable().get(), where + "/rate_table");
        }
        if (container.deformableBoundary().isPresent()) {
            flatBag(container, where);
        }
        if (container.madeToMeasure().isPresent()) {
            madeToMeasure(container, container.madeToMeasure().get(), where);
        }
    }

    private static void rateTable(RateTable table, String where) {
        for (int index = 0; index < table.weightBracketsG().size(); index++) {
            RequestRules.integer(table.weightBracketsG().get(index), where + "/weight_brackets_g/" + index, 1);
        }
        for (int index = 0; index < table.pricesMinor().size(); index++) {
            RequestRules.integer(table.pricesMinor().get(index), where + "/prices_minor/" + index, 0);
        }
        RequestRules.integer(table.minimumChargeMinor(), where + "/minimum_charge_minor", 0);
        RequestRules.integer(table.fuelSurchargePermille(), where + "/fuel_surcharge_permille", 0);
    }

    /**
     * The fields a flat bag refuses, in the JSON rules' order. The container's constructor already
     * refused an unknown kind and every field but {@code made_to_measure}, with no field named;
     * walking them all keeps the order the JSON path reports in. An empty list is how an object
     * leaves a list field out; JSON leaves it absent.
     */
    private static void flatBag(Container container, String where) {
        RequestRules.oneOf(container.deformableBoundary().get(), where + "/deformable_boundary/kind", FlatBag.KINDS);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("obstacles", container.obstacles().isEmpty() ? null : container.obstacles());
        fields.put("axles", container.axles().orElse(null));
        fields.put("access_directions", container.accessDirections().isEmpty() ? null : container.accessDirections());
        fields.put("max_stack_density", container.maxStackDensity().orElse(null));
        fields.put("outer_dimensions", container.outerDimensions().orElse(null));
        fields.put("void_fill_reserve_ratio", container.voidFillReserveRatio());
        fields.put("made_to_measure", container.madeToMeasure().orElse(null));
        for (String name : RequestRules.BAG_EXCLUSIONS) {
            if (RequestRules.setBesideABag(name, fields.get(name))) {
                throw new InvalidRequestException("not_allowed", where + "/" + name,
                        "cannot be combined with deformable_boundary");
            }
        }
    }

    private static void madeToMeasure(Container container, MadeToMeasure machine, String where) {
        String price = where + "/made_to_measure/cost_per_square_metre_minor";
        RequestRules.integer(machine.costPerSquareMetreMinor(), price, 0);
        if (container.outerDimensions().isPresent()) {
            throw new InvalidRequestException("not_allowed", where + "/outer_dimensions",
                    "cannot be combined with made_to_measure");
        }
        if (MadeToMeasure.boxCostFor(machine.costPerSquareMetreMinor(), container.costMinor(),
                Sides.of(container.innerDimensions())).compareTo(MadeToMeasure.MAX_EXACT_INTEGER) > 0) {
            throw new InvalidRequestException("invalid_value", price,
                    "prices the largest box above " + CanonicalJson.MAX_EXACT_MAGNITUDE);
        }
    }

    /**
     * The fixed-placement shape rules an object can break, in their order and with their refusal;
     * whether the set can hold is {@link com.packvium.algorithm.FixedPlacementAdmission}'s to say.
     * A {@link com.packvium.domain.Point} already refuses a negative coordinate.
     */
    private static void fixedPlacement(FixedPlacement placement, String where, String field) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("item_type", placement.itemId());
        names.put("container_type", placement.containerId());
        for (Map.Entry<String, String> name : names.entrySet()) {
            if (name.getValue() == null || name.getValue().isEmpty()) {
                throw malformed(where + "." + name.getKey() + " is a non-empty string", field + "/" + name.getKey());
            }
        }
        if (placement.rotation() == null) {
            throw malformed(where + ".orientation " + FixedPlacementShape.orientationRule(), field + "/orientation");
        }
        Optional<Long> instance = JsonValue.integer(placement.containerInstance());
        if (instance.isEmpty() || instance.get() < 1) {
            throw malformed(where + ".container_instance counts from 1", field + "/container_instance");
        }
        if (placement.position() == null) {
            throw malformed(where + ".position is a point object", field + "/position");
        }
    }

    private static FixedPlacementException malformed(String detail, String field) {
        return new FixedPlacementException(detail, "malformed", field);
    }

    private static void optionalInteger(Optional<Long> value, String field, long minimum) {
        if (value.isPresent()) {
            RequestRules.integer(value.get(), field, minimum);
        }
    }

    private static List<String> codePointOrder(Iterable<String> values) {
        List<String> ordered = new ArrayList<>();
        values.forEach(ordered::add);
        ordered.sort(SortKey::compareCodePoints);
        return ordered;
    }
}
