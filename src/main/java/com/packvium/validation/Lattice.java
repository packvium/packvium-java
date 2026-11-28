package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact lattice results, expanded independently before ordinary validation. The summary is
 * untrusted: its parameters must agree with the request before they are used to synthesize
 * placements, which then pass through the same checks as explicit ones. O(count) per summary.
 */
final class Lattice {
    private static final List<String> SIDES = List.of("length", "width", "height");
    private static final List<String> POSITIVE = List.of("nx", "ny", "layers_used", "layer_step", "count");

    private Lattice() {
    }

    /** The parameters a summary is read by. */
    private record Fields(Object orientation, List<Object> physical, List<Object> envelope, Map<String, Object> counts) {
        Object count(String name) {
            return counts.get(name);
        }
    }

    /** The result with every valid summary expanded into placements; the result itself is not changed. */
    static Object materialize(Object request, Object result, Ordered<ItemSpec> items, Ordered<ContainerSpec> containers,
            BigInteger clearance, List<ResultIssue> issues) {
        List<?> packedContainers = Json.items(Json.at(result, "containers"), "result.containers");
        if (packedContainers.stream().noneMatch(packed -> Json.has(packed, "lattice_summary"))) {
            return result;
        }
        boolean optional = Boolean.FALSE.equals(
                Json.get(Json.get(request, "configuration", Map.of()), "require_placement_coordinates"));
        List<Object> expanded = new ArrayList<>();
        for (Object packed : packedContainers) {
            List<Object> placements = expand(packed, optional, items, containers, clearance, issues);
            if (placements == null) {
                expanded.add(packed);
            } else {
                Map<Object, Object> copy = new LinkedHashMap<>(Json.dict(packed));
                copy.put("placements", placements);
                expanded.add(copy);
            }
        }
        Map<Object, Object> copy = new LinkedHashMap<>(Json.dict(result));
        copy.put("containers", expanded);
        return copy;
    }

    private static List<Object> expand(Object packed, boolean optional, Ordered<ItemSpec> items,
            Ordered<ContainerSpec> containers, BigInteger clearance, List<ResultIssue> issues) {
        Object summary = Json.get(packed, "lattice_summary");
        if (summary == null) {
            return null;
        }
        String containerId = PyFormat.str(Json.get(packed, "id", "?"));
        if (!optional) {
            issues.add(new ResultIssue("unexpected_lattice_summary", containerId
                    + ": request requires explicit placement coordinates"));
        }
        if (Json.truthy(Json.get(packed, "placements"))) {
            issues.add(new ResultIssue("lattice_placements_conflict", containerId
                    + ": compact summary and explicit placements are mutually exclusive"));
        }
        Object itemType = Json.get(summary, "item_type");
        ItemSpec item = items.get(itemType);
        ContainerSpec container = containers.get(Json.get(packed, "container_type"));
        if (item == null || container == null) {
            // The ordinary accounting and container checks report the unknown reference.
            return null;
        }
        Fields fields = fields(summary, containerId);
        List<String> problems = problems(fields, item, itemType, container, clearance);
        if (!problems.isEmpty()) {
            problems.forEach(problem -> issues.add(new ResultIssue("lattice_summary_mismatch", containerId + ": " + problem)));
            return List.of();
        }
        return synthesize(fields, summary, itemType, containerId, clearance);
    }

    private static Fields fields(Object summary, String containerId) {
        try {
            Map<String, Object> counts = new LinkedHashMap<>();
            Object orientation = Json.at(summary, "orientation");
            List<Object> physical = sides(summary, "physical_dimensions");
            List<Object> envelope = sides(summary, "envelope_dimensions");
            counts.put("nx", Json.at(summary, "nx"));
            counts.put("ny", Json.at(summary, "ny"));
            counts.put("layers_used", Json.at(summary, "layers_used"));
            counts.put("layer_step", Json.at(Json.at(summary, "layer_step"), "ticks"));
            counts.put("count", Json.at(summary, "count"));
            return new Fields(orientation, physical, envelope, counts);
        } catch (MalformedResult missing) {
            // The reference reports a missing field as a contract break, naming the container.
            throw MalformedResult.contract(containerId + ": lattice_summary is missing a canonical field: "
                    + missing.getMessage());
        }
    }

    private static List<Object> sides(Object summary, String key) {
        Object block = Json.at(summary, key);
        List<Object> sides = new ArrayList<>();
        for (String side : SIDES) {
            sides.add(Json.at(Json.at(block, side), "ticks"));
        }
        return sides;
    }

    private static List<String> problems(Fields fields, ItemSpec item, Object itemType, ContainerSpec container,
            BigInteger clearance) {
        List<String> problems = new ArrayList<>();
        Object orientation = fields.orientation();
        if (!Geometry.isRotation(orientation)) {
            problems.add("unknown orientation " + PyFormat.repr(orientation));
        } else {
            List<BigInteger> expected = item.rotated((String) orientation);
            if (!matches(fields.physical(), expected)) {
                problems.add("physical dimensions " + PyFormat.valueTuple(fields.physical()) + " != rotated item "
                        + PyFormat.intTuple(expected));
            }
            if (!item.allows(orientation)) {
                problems.add("orientation " + orientation + " is forbidden for " + PyFormat.str(itemType));
            }
        }
        BigInteger margin = clearance.multiply(Exact.TWO);
        List<BigInteger> expectedEnvelope = new ArrayList<>();
        for (Object side : fields.physical()) {
            expectedEnvelope.add(Json.integer(side, "physical dimension").add(margin));
        }
        if (!matches(fields.envelope(), expectedEnvelope)) {
            problems.add("envelope dimensions " + PyFormat.valueTuple(fields.envelope()) + " != physical+clearance "
                    + PyFormat.intTuple(expectedEnvelope));
        }
        if (item.nestingHeight() != null) {
            problems.add("compact lattice form is not valid for nesting items");
        }
        if (!Json.equals(fields.count("layer_step"), fields.envelope().get(2))) {
            problems.add("layer_step " + PyFormat.str(fields.count("layer_step")) + " != envelope height "
                    + PyFormat.str(fields.envelope().get(2)));
        }
        for (String name : POSITIVE) {
            BigInteger value = Json.strictInt(fields.count(name));
            if (value == null || value.signum() < 1) {
                problems.add(name + " must be a positive integer, got " + PyFormat.repr(fields.count(name)));
            }
        }
        if (problems.isEmpty()) {
            independentCounts(fields, item, container, problems);
        }
        return problems;
    }

    /** Reached only once every field is a positive integer and the envelope is the expected one. */
    private static void independentCounts(Fields fields, ItemSpec item, ContainerSpec container, List<String> problems) {
        BigInteger nx = Json.strictInt(fields.count("nx"));
        BigInteger ny = Json.strictInt(fields.count("ny"));
        BigInteger layersUsed = Json.strictInt(fields.count("layers_used"));
        BigInteger layerStep = Json.strictInt(fields.count("layer_step"));
        BigInteger count = Json.strictInt(fields.count("count"));
        BigInteger expectedNx = Exact.floorDiv(container.inner().get(0), Json.strictInt(fields.envelope().get(0)));
        BigInteger expectedNy = Exact.floorDiv(container.inner().get(1), Json.strictInt(fields.envelope().get(1)));
        BigInteger expectedLayers = Exact.ceilDiv(count, nx.multiply(ny));
        if (!nx.equals(expectedNx)) {
            problems.add("nx " + nx + " != independently computed " + expectedNx);
        }
        if (!ny.equals(expectedNy)) {
            problems.add("ny " + ny + " != independently computed " + expectedNy);
        }
        if (!layersUsed.equals(expectedLayers)) {
            problems.add("layers_used " + layersUsed + " != independently computed " + expectedLayers);
        }
        if (count.compareTo(Json.integer(item.quantity(), "quantity")) > 0) {
            problems.add("count " + count + " exceeds requested quantity " + PyFormat.str(item.quantity()));
        }
        BigInteger capacity = Exact.floorDiv(container.inner().get(2), layerStep);
        if (layersUsed.compareTo(capacity) > 0) {
            problems.add("layers_used " + layersUsed + " exceeds vertical capacity " + capacity);
        }
    }

    private static List<Object> synthesize(Fields fields, Object summary, Object itemType, String containerId,
            BigInteger clearance) {
        long nx = Json.strictInt(fields.count("nx")).longValueExact();
        long ny = Json.strictInt(fields.count("ny")).longValueExact();
        long count = Json.strictInt(fields.count("count")).longValueExact();
        BigInteger stepX = Json.strictInt(fields.envelope().get(0));
        BigInteger stepY = Json.strictInt(fields.envelope().get(1));
        BigInteger stepZ = Json.strictInt(fields.count("layer_step"));
        Object physical = Json.at(summary, "physical_dimensions");
        List<Object> placements = new ArrayList<>();
        for (long index = 0; index < count; index++) {
            Map<String, Object> position = new LinkedHashMap<>();
            position.put("x", Map.of("ticks", BigInteger.valueOf(index % nx).multiply(stepX).add(clearance)));
            position.put("y", Map.of("ticks", BigInteger.valueOf((index / nx) % ny).multiply(stepY).add(clearance)));
            position.put("z", Map.of("ticks", BigInteger.valueOf(index / (nx * ny)).multiply(stepZ).add(clearance)));
            Map<String, Object> placement = new LinkedHashMap<>();
            placement.put("item_id", PyFormat.str(itemType) + "#lattice:" + containerId + ":" + (index + 1));
            placement.put("item_type", itemType);
            placement.put("orientation", fields.orientation());
            placement.put("position", position);
            placement.put("dimensions", physical);
            placements.add(placement);
        }
        return placements;
    }

    private static boolean matches(List<Object> reported, List<BigInteger> expected) {
        for (int index = 0; index < expected.size(); index++) {
            if (!Json.equalsInt(reported.get(index), expected.get(index))) {
                return false;
            }
        }
        return true;
    }
}
