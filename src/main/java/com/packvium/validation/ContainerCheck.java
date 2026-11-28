package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One packed container: geometry, loads, accounting figures, and a made-to-measure box. */
final class ContainerCheck {
    /** Ratios in the objective vector and the reserve are carried as parts per million. */
    static final BigInteger SCORE_SCALE = BigInteger.valueOf(1_000_000);
    private static final List<String> SIDES = List.of("length", "width", "height");

    private ContainerCheck() {
    }

    /**
     * The physical box grown by the clearance margin. A placement without integer tick
     * coordinates is the reference's contract refusal, worded as it words it.
     */
    static Cuboid envelope(Object placement, BigInteger clearance) {
        try {
            Object position = Json.at(placement, "position");
            Object dimensions = Json.at(placement, "dimensions");
            BigInteger margin = clearance.multiply(Exact.TWO);
            return new Cuboid(tick(position, "x").subtract(clearance), tick(position, "y").subtract(clearance),
                    tick(position, "z").subtract(clearance), tick(dimensions, "length").add(margin),
                    tick(dimensions, "width").add(margin), tick(dimensions, "height").add(margin));
        } catch (MalformedResult missing) {
            throw MalformedResult.contract("placement is missing tick coordinates: " + missing.getMessage());
        }
    }

    private static BigInteger tick(Object block, String key) {
        return Json.integer(Json.at(Json.at(block, key), "ticks"), "placement ticks");
    }

    /** The largest envelope x2, y2 and z2 over a container's placements, or zeros for none. */
    static List<BigInteger> achievedExtent(Object packed, BigInteger clearance) {
        BigInteger[] extent = null;
        for (Object placement : Json.items(Json.at(packed, "placements"), "placements")) {
            Cuboid envelope = envelope(placement, clearance);
            extent = extent == null ? new BigInteger[] {envelope.x2, envelope.y2, envelope.z2}
                    : new BigInteger[] {extent[0].max(envelope.x2), extent[1].max(envelope.y2), extent[2].max(envelope.z2)};
        }
        return extent == null ? List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO) : List.of(extent);
    }

    /**
     * {@code achieved_dimensions} is present exactly when the container declares a soft size, and
     * equals the tight extent; a flat bag's extent must pass the girth rule.
     */
    static void checkAchievedExtent(Object packed, ContainerSpec spec, BigInteger clearance, List<ResultIssue> issues) {
        Object reported = Json.get(packed, "achieved_dimensions");
        Label packedId = new Label(packed);
        if (!spec.reportsAchievedExtent()) {
            if (reported != null) {
                issues.add(new ResultIssue("achieved_dimensions_mismatch", packedId + ": reported for a container of fixed size"));
            }
            packedId.verify();
            return;
        }
        List<BigInteger> extent = achievedExtent(packed, clearance);
        List<Object> stated = null;
        if (Json.isDict(reported)) {
            stated = new ArrayList<>();
            for (String side : SIDES) {
                stated.add(Json.get(Json.get(reported, side, Map.of()), "ticks"));
            }
        }
        if (!matches(stated, extent)) {
            issues.add(new ResultIssue("achieved_dimensions_mismatch", packedId + ": reported " + optionalTuple(stated)
                    + ", computed " + PyFormat.intTuple(extent)));
        }
        BigInteger lengthGirth = extent.get(0).add(extent.get(2));
        BigInteger widthGirth = extent.get(1).add(extent.get(2));
        if (spec.flatBag() && (lengthGirth.compareTo(spec.inner().get(0)) > 0 || widthGirth.compareTo(spec.inner().get(1)) > 0)) {
            issues.add(new ResultIssue("bag_girth_exceeded", packedId + ": a + c = " + lengthGirth + ", b + c = "
                    + widthGirth + ", flat size " + spec.inner().get(0) + " x " + spec.inner().get(1)));
        }
        packedId.verify();
    }

    private static boolean matches(List<?> stated, List<BigInteger> expected) {
        if (stated == null) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!Json.equalsInt(stated.get(index), expected.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static String optionalTuple(List<?> values) {
        return values == null ? "None" : PyFormat.valueTuple(values);
    }

    /** {@code (value.get(key) or {}).get("ticks")} per side, or null when {@code value} is not an object. */
    private static List<Object> reportedTicks(Object value) {
        if (!Json.isDict(value)) {
            return null;
        }
        List<Object> ticks = new ArrayList<>();
        for (String side : SIDES) {
            ticks.add(nestedTicks(value, side));
        }
        return ticks;
    }

    /** {@code (block.get(key) or {}).get("ticks")}. */
    private static Object nestedTicks(Object block, String key) {
        Object value = Json.get(block, key);
        return Json.truthy(value) ? Json.get(value, "ticks") : null;
    }

    /**
     * Recompute a made-to-measure container's box from its placements alone and return the spec
     * every later check uses: the recomputed box, never the reported one. O(p) for p placements.
     */
    static ContainerSpec checkMadeToMeasure(Object packed, boolean compact, ContainerSpec spec, Ordered<ItemSpec> items,
            BigInteger clearance, List<ResultIssue> issues) {
        String containerId = PyFormat.str(Json.get(packed, "id", "?"));
        Object block = Json.get(packed, "made_to_measure");
        MachineSpec machine = spec.madeToMeasure();
        if (machine == null) {
            if (block != null) {
                issues.add(new ResultIssue("made_to_measure_unexpected", containerId));
            }
            return spec;
        }
        if (!Json.isDict(block)) {
            issues.add(new ResultIssue("made_to_measure_missing", containerId));
            block = Map.of();
        }
        if (compact) {
            issues.add(new ResultIssue("lattice_summary_on_made_to_measure", containerId));
        }
        List<BigInteger> achieved = achievedExtent(packed, clearance);
        List<BigInteger> fabricated = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            BigInteger minimum = machine.minimum().get(axis);
            BigInteger increment = machine.increment().get(axis);
            BigInteger steps = Exact.ceilDiv(achieved.get(axis).max(minimum).subtract(minimum), increment);
            fabricated.add(minimum.add(steps.multiply(increment)));
        }
        for (int axis = 0; axis < 3; axis++) {
            if (fabricated.get(axis).compareTo(spec.inner().get(axis)) > 0) {
                issues.add(new ResultIssue("fabricated_dimensions_exceed_machine", containerId + ": "
                        + PyFormat.intTuple(fabricated) + " beyond " + PyFormat.intTuple(spec.inner())));
                break;
            }
        }
        List<Object> reportedInner = reportedTicks(Json.get(packed, "inner_dimensions"));
        if (!matches(reportedInner, fabricated)) {
            issues.add(new ResultIssue("fabricated_dimensions_mismatch", containerId + ": reported "
                    + optionalTuple(reportedInner) + ", computed " + PyFormat.intTuple(fabricated)));
        }
        List<BigInteger> outer = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            outer.add(fabricated.get(axis).add(machine.allowance().get(axis)));
        }
        List<Object> reportedOuter = reportedTicks(Json.get(packed, "outer_dimensions"));
        if (!matches(reportedOuter, outer)) {
            issues.add(new ResultIssue("outer_dimensions_mismatch", containerId + ": reported " + optionalTuple(reportedOuter)
                    + ", computed " + PyFormat.intTuple(outer)));
        }
        BigInteger cost = spec.costMinor().add(perSquareMetreCeiling(machine.pricePerSquareMetreMinor(), fabricated));
        Object reportedCost = Json.get(block, "cost_minor");
        if (!Json.equalsInt(reportedCost, cost)) {
            issues.add(new ResultIssue("made_to_measure_cost_mismatch", containerId + ": reported "
                    + PyFormat.str(reportedCost) + ", computed " + cost));
        }
        BigInteger tare = spec.tareWeight().add(perSquareMetreCeiling(machine.tarePerSquareMetre(), fabricated));
        Object reportedTare = nestedTicks(block, "tare_weight");
        if (!Json.equalsInt(reportedTare, tare)) {
            issues.add(new ResultIssue("made_to_measure_tare_mismatch", containerId + ": reported "
                    + PyFormat.str(reportedTare) + ", computed " + tare));
        }
        BigInteger gross = tare.add(payloadOf(packed, items));
        Object reportedGross = nestedTicks(packed, "gross_weight");
        if (!Json.equalsInt(reportedGross, gross)) {
            issues.add(new ResultIssue("gross_weight_mismatch", containerId + ": reported " + PyFormat.str(reportedGross)
                    + ", computed " + gross));
        }
        return spec.fabricated(fabricated, outer, cost, tare);
    }

    /** {@code ceil(rate * 2(LW + LH + WH) / 1 m^2)}. */
    private static BigInteger perSquareMetreCeiling(BigInteger rate, List<BigInteger> sides) {
        BigInteger length = sides.get(0);
        BigInteger width = sides.get(1);
        BigInteger height = sides.get(2);
        BigInteger area = length.multiply(width).add(length.multiply(height)).add(width.multiply(height)).multiply(Exact.TWO);
        return Exact.ceilDiv(rate.multiply(area), Stacking.SQUARE_METRE_TICKS);
    }

    /** The summed weight of every placement whose type the request declares. */
    static BigInteger payloadOf(Object packed, Ordered<ItemSpec> items) {
        BigInteger payload = BigInteger.ZERO;
        for (Object placement : Json.items(Json.at(packed, "placements"), "placements")) {
            ItemSpec spec = items.get(Json.at(placement, "item_type"));
            if (spec != null) {
                payload = payload.add(spec.weight());
            }
        }
        return payload;
    }

    static void check(Object packed, ContainerSpec spec, Ordered<ItemSpec> items, BigInteger clearance,
            Ratio globalSupport, List<ResultIssue> issues) {
        Label packedId = new Label(packed);
        List<?> placements = Json.items(Json.at(packed, "placements"), "placements");
        Cuboid boundary = new Cuboid(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, spec.inner().get(0),
                spec.inner().get(1), spec.inner().get(2));
        Ordered<Entry> byId = new Ordered<>();
        BigInteger payload = BigInteger.ZERO;
        for (Object placement : placements) {
            Object itemId = Json.at(placement, "item_id");
            ItemSpec itemSpec = items.get(Json.at(placement, "item_type"));
            Cuboid envelope = envelope(placement, clearance);
            byId.set(itemId, new Entry(itemId, envelope, placement, itemSpec));
            String label = PyFormat.str(itemId);
            if (!boundary.contains(envelope)) {
                issues.add(new ResultIssue("outside_container", label + " in " + packedId));
            }
            for (Cuboid obstacle : spec.obstacles()) {
                if (envelope.intersects(obstacle)
                        && Geometry.solidsOverlap(Stacking.placedHull(envelope, placement, itemSpec), obstacle.vertices())) {
                    issues.add(new ResultIssue("obstacle_collision", label + " in " + packedId));
                }
            }
            if (itemSpec == null) {
                continue;
            }
            payload = payload.add(itemSpec.weight());
            checkRotation(placement, itemSpec, label, issues);
            if (!itemSpec.eligibleContainerTags().isEmpty() && disjoint(itemSpec.eligibleContainerTags(), spec.tags())) {
                List<String> tags = Json.sorted(new ArrayList<>(itemSpec.eligibleContainerTags()));
                issues.add(new ResultIssue("container_ineligible", label + ": " + PyFormat.str(Json.at(packed, "container_type"))
                        + " has none of " + PyFormat.listOf(tags.stream().map(PyFormat::string).toList())));
            }
            if (itemSpec.mustBeOnFloor() && envelope.z.signum() != 0) {
                issues.add(new ResultIssue("floor_violation", label + " at z=" + envelope.z));
            }
        }
        List<Entry> entries = byId.values();
        checkPairs(entries, packedId, issues);
        Stacking.Graph graph = Stacking.supportGraph(entries);
        Stacking.checkSupportAndLoad(entries, graph, globalSupport, issues);
        List<BigInteger> loads = Stacking.propagateLoad(entries, graph);
        if (spec.maxStackDensity() != null) {
            Stacking.checkStackDensity(packedId, entries, loads, spec.maxStackDensity(), issues);
        }
        Stacking.checkCrush(packedId, entries, loads, issues);
        Stacking.checkRouteRemovalOrder(packedId, entries, graph, spec.inner(), spec.accessDirections(), issues);
        checkLimits(packed, packedId, placements, spec, items, payload, issues);
        checkVolumes(packed, packedId, placements, spec, items, byId, loads, issues);
        BigInteger totalWeight = payloadOf(packed, items);
        checkCentreOfMass(packed, packedId, placements, spec, items, totalWeight, issues);
        checkAxles(packed, packedId, placements, spec, items, totalWeight, issues);
        packedId.verify();
    }

    static boolean disjoint(Set<String> left, Set<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static void checkRotation(Object placement, ItemSpec spec, String itemId, List<ResultIssue> issues) {
        Object orientation = Json.at(placement, "orientation");
        if (!Geometry.isRotation(orientation)) {
            issues.add(new ResultIssue("unknown_orientation", itemId + ": " + PyFormat.str(orientation)));
            return;
        }
        String code = (String) orientation;
        if (!spec.allows(code)) {
            issues.add(new ResultIssue("forbidden_rotation", itemId + ": " + code));
        }
        List<BigInteger> expected = spec.rotated(code);
        Object dimensions = Json.at(placement, "dimensions");
        List<Object> actual = new ArrayList<>();
        for (String side : SIDES) {
            actual.add(Json.at(Json.at(dimensions, side), "ticks"));
        }
        if (!matches(actual, expected)) {
            issues.add(new ResultIssue("dimension_mismatch", itemId + ": " + code + " of "
                    + PyFormat.intTuple(spec.dimensions()) + " is " + PyFormat.intTuple(expected) + ", got "
                    + PyFormat.valueTuple(actual)));
        }
    }

    private static void checkPairs(List<Entry> entries, Label packedId, List<ResultIssue> issues) {
        for (int first = 0; first < entries.size(); first++) {
            Entry left = entries.get(first);
            for (int second = first + 1; second < entries.size(); second++) {
                Entry right = entries.get(second);
                if (left.envelope().intersects(right.envelope())
                        && Geometry.solidsOverlap(Stacking.placedHull(left.envelope(), left.placement(), left.spec()),
                                Stacking.placedHull(right.envelope(), right.placement(), right.spec()))
                        && !Stacking.validNesting(left, right)) {
                    issues.add(new ResultIssue("collision", PyFormat.str(left.id()) + " with " + PyFormat.str(right.id())
                            + " in " + packedId));
                }
                if (left.spec() != null && right.spec() != null
                        && (!disjoint(left.spec().incompatibleTags(), right.spec().tags())
                                || !disjoint(right.spec().incompatibleTags(), left.spec().tags()))) {
                    issues.add(new ResultIssue("incompatible_items", PyFormat.str(left.id()) + " is incompatible with "
                            + PyFormat.str(right.id()) + " in " + packedId));
                }
            }
        }
    }

    private static void checkLimits(Object packed, Label packedId, List<?> placements, ContainerSpec spec,
            Ordered<ItemSpec> items, BigInteger payload, List<ResultIssue> issues) {
        if (spec.maxPayload() != null && payload.compareTo(spec.maxPayload()) > 0) {
            issues.add(new ResultIssue("payload_exceeded", packedId + ": " + payload + " > " + spec.maxPayload() + " ticks"));
        }
        Object reported = Json.get(Json.get(packed, "payload_weight", Map.of()), "ticks");
        if (reported != null && !Json.equalsInt(reported, payload)) {
            issues.add(new ResultIssue("payload_weight_mismatch", packedId + ": reported " + PyFormat.str(reported)
                    + ", computed " + payload));
        }
        if (spec.maxItems() != null && BigInteger.valueOf(placements.size()).compareTo(spec.maxItems()) > 0) {
            issues.add(new ResultIssue("max_items_exceeded", packedId + ": " + placements.size() + " > " + spec.maxItems()));
        }
        for (Map.Entry<String, BigInteger> limit : spec.tagLimits()) {
            long count = 0;
            for (Object placement : placements) {
                ItemSpec item = items.get(Json.at(placement, "item_type"));
                if (item != null && item.tags().contains(limit.getKey())) {
                    count++;
                }
            }
            if (BigInteger.valueOf(count).compareTo(limit.getValue()) > 0) {
                issues.add(new ResultIssue("tag_count_exceeded", packedId + ": " + limit.getKey() + " count " + count
                        + " > limit " + limit.getValue()));
            }
        }
    }

    private static void checkVolumes(Object packed, Label packedId, List<?> placements, ContainerSpec spec,
            Ordered<ItemSpec> items, Ordered<Entry> byId, List<BigInteger> loads, List<ResultIssue> issues) {
        // Every placement counts, through the envelope its item id resolves to: a repeated id
        // resolves to its last placement, as the reference's dict does.
        BigInteger used = BigInteger.ZERO;
        for (Object placement : placements) {
            Object itemId = Json.at(placement, "item_id");
            Entry entry = byId.get(itemId);
            used = used.add(Stacking.occupiedVolume(entry.envelope(), placement,
                    items.get(Json.at(placement, "item_type")), loads.get(byId.position(itemId))));
        }
        List<Entry> entries = byId.values();
        for (int first = 0; first < entries.size(); first++) {
            for (int second = first + 1; second < entries.size(); second++) {
                if (Stacking.validNesting(entries.get(first), entries.get(second))) {
                    Entry left = entries.get(first);
                    used = used.subtract(left.spec().nestingHeight().multiply(left.envelope().length)
                            .multiply(left.envelope().width));
                }
            }
        }
        Object reportedVolume = Json.get(packed, "used_volume_ticks3");
        if (reportedVolume != null && !Json.pyInt(reportedVolume, "used_volume_ticks3").equals(used)) {
            issues.add(new ResultIssue("used_volume_mismatch", packedId + ": reported " + PyFormat.str(reportedVolume)
                    + ", computed " + used));
        }
        BigInteger volume = spec.volume();
        BigInteger scaledReserve = ratioPpm(spec.voidFillReserveRatio());
        BigInteger reserved = Exact.floorDiv(volume, SCORE_SCALE).multiply(scaledReserve)
                .add(Exact.floorDiv(Exact.mod(volume, SCORE_SCALE).multiply(scaledReserve), SCORE_SCALE));
        Object reportedReserve = Json.get(packed, "void_fill_reserve_ticks3");
        if (reportedReserve != null && !Json.pyInt(reportedReserve, "void_fill_reserve_ticks3").equals(reserved)) {
            issues.add(new ResultIssue("void_fill_reserve_mismatch", packedId + ": reported " + PyFormat.str(reportedReserve)
                    + ", computed " + reserved));
        } else if (reserved.signum() != 0 && reportedReserve == null) {
            issues.add(new ResultIssue("void_fill_reserve_mismatch", packedId + ": non-zero reserve " + reserved
                    + " is not reported"));
        }
        BigInteger usable = volume.subtract(reserved);
        if (used.compareTo(usable) > 0) {
            issues.add(new ResultIssue("void_fill_reserve_exceeded", packedId + ": used " + used + " > usable " + usable));
        }
    }

    /**
     * A support, void-fill reserve or compression ratio in parts per million exactly as every
     * solver converts it: the IEEE double {@code floor(ratio * 10^6 + 0.5)}. Rounding the exact
     * rational instead disagrees on about one in a hundred seven-decimal halves.
     */
    static BigInteger ratioPpm(Ratio ratio) {
        double scaled = ratio.toDouble() * 1_000_000 + 0.5;
        if (!Double.isFinite(scaled)) {
            throw MalformedResult.shape("cannot convert float infinity to integer");
        }
        return new java.math.BigDecimal(scaled).toBigInteger();
    }

    /** {@code sum(weight * (2 * position + extent))} along one axis, over declared item types. */
    private static BigInteger doubledMoment(List<?> placements, Ordered<ItemSpec> items, String axis, String side) {
        BigInteger moment = BigInteger.ZERO;
        for (Object placement : placements) {
            ItemSpec spec = items.get(Json.at(placement, "item_type"));
            if (spec == null) {
                continue;
            }
            BigInteger position = Json.integer(Json.at(Json.at(Json.at(placement, "position"), axis), "ticks"), "placement ticks");
            BigInteger extent = Json.integer(Json.at(Json.at(Json.at(placement, "dimensions"), side), "ticks"), "placement ticks");
            moment = moment.add(spec.weight().multiply(position.multiply(Exact.TWO).add(extent)));
        }
        return moment;
    }

    private static void checkCentreOfMass(Object packed, Label packedId, List<?> placements, ContainerSpec spec,
            Ordered<ItemSpec> items, BigInteger totalWeight, List<ResultIssue> issues) {
        BigInteger expected = BigInteger.ZERO;
        if (totalWeight.signum() != 0) {
            expected = offset(doubledMoment(placements, items, "x", "length"), totalWeight, spec.inner().get(0))
                    .max(offset(doubledMoment(placements, items, "y", "width"), totalWeight, spec.inner().get(1)));
        }
        Object reported = Json.get(packed, "centre_of_mass_offset_ppm");
        if (!Json.equalsInt(reported, expected)) {
            issues.add(new ResultIssue("centre_of_mass_mismatch", packedId + ": reported " + PyFormat.str(reported)
                    + ", computed " + expected));
        }
    }

    private static BigInteger offset(BigInteger doubled, BigInteger totalWeight, BigInteger side) {
        BigInteger centred = totalWeight.multiply(side);
        return Exact.floorDiv(doubled.subtract(centred).abs().multiply(SCORE_SCALE), centred);
    }

    private static void checkAxles(Object packed, Label packedId, List<?> placements, ContainerSpec spec,
            Ordered<ItemSpec> items, BigInteger totalWeight, List<ResultIssue> issues) {
        if (spec.axles() == null) {
            if (Json.has(packed, "axle_reactions")) {
                issues.add(new ResultIssue("axle_reaction_mismatch", packedId + ": reactions reported without configured axles"));
            }
            return;
        }
        if (spec.axles().size() != 2) {
            throw MalformedResult.shape(packedId + ": axles must name exactly a front and a rear axle");
        }
        BigInteger frontX = spec.axles().get(0)[0];
        BigInteger frontLimit = spec.axles().get(0)[1];
        BigInteger rearX = spec.axles().get(1)[0];
        BigInteger rearLimit = spec.axles().get(1)[1];
        BigInteger denominator = rearX.subtract(frontX).multiply(Exact.TWO);
        BigInteger gross = spec.tareWeight().add(totalWeight);
        BigInteger doubledWeightedX = spec.tareWeight().multiply(spec.inner().get(0))
                .add(doubledMoment(placements, items, "x", "length"));
        BigInteger frontNumerator = gross.multiply(Exact.TWO).multiply(rearX).subtract(doubledWeightedX);
        BigInteger rearNumerator = doubledWeightedX.subtract(gross.multiply(Exact.TWO).multiply(frontX));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("basis", "gross");
        expected.put("denominator", denominator.toString());
        expected.put("front_numerator", frontNumerator.toString());
        expected.put("rear_numerator", rearNumerator.toString());
        Object reported = Json.get(packed, "axle_reactions");
        if (!Json.equals(reported, expected)) {
            issues.add(new ResultIssue("axle_reaction_mismatch", packedId + ": reported " + PyFormat.str(reported)
                    + ", computed " + PyFormat.repr(expected)));
        }
        if (frontLimit != null && frontNumerator.compareTo(frontLimit.multiply(denominator)) > 0
                || rearLimit != null && rearNumerator.compareTo(rearLimit.multiply(denominator)) > 0) {
            issues.add(new ResultIssue("axle_overloaded", packedId.toString()));
        }
    }
}
