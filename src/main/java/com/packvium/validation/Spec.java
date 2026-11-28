package com.packvium.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The request, read independently of the engine's own model. */
final class Spec {
    /** The six axis-aligned exit directions a placement could be slid out through. */
    static final List<String> EXIT_DIRECTIONS = List.of("+x", "-x", "+y", "-y", "+z", "-z");
    private static final List<String> UPRIGHT_ROTATIONS = List.of("LWH", "WLH");

    private Spec() {
    }

    static Ordered<ItemSpec> items(Object request, String unit) {
        Ordered<ItemSpec> items = new Ordered<>();
        for (Object raw : Json.items(Json.at(request, "items"), "request.items")) {
            Object rotations = Json.get(raw, "allowed_rotations");
            List<?> allowed = rotations == null
                    ? (Json.truthy(Json.get(raw, "keep_upright")) ? UPRIGHT_ROTATIONS : Geometry.ORIENTATIONS)
                    : Json.items(rotations, "allowed_rotations");
            Object id = Json.at(raw, "id");
            Object ratio = Json.get(raw, "compression_ratio");
            Object accepted = Json.get(raw, "accepts_stacking_groups");
            ItemSpec spec = new ItemSpec(
                    Json.text(id, "an item id"),
                    Units.dimensions(Json.at(raw, "dimensions"), unit),
                    Units.toTicks(Json.get(raw, "weight", 0L), "g", Units.WEIGHT),
                    Json.get(raw, "quantity", 1L),
                    List.copyOf(allowed),
                    Json.truthy(Json.get(raw, "stackable", true)),
                    Json.truthy(Json.get(raw, "must_be_on_floor")),
                    measured(raw, "max_top_load", "g", Units.WEIGHT),
                    Units.ratioOf(Json.get(raw, "minimum_support_ratio", 0L)),
                    tagSet(Json.get(raw, "tags", List.of()), "tags"),
                    tagSet(Json.get(raw, "eligible_container_tags", List.of()), "eligible_container_tags"),
                    Json.optionalInteger(Json.get(raw, "max_stacked_items"), "max_stacked_items"),
                    Json.get(raw, "ground_contact_rule"),
                    measured(raw, "nesting_height", unit, Units.LENGTH),
                    tagSet(Json.get(raw, "incompatible_tags", List.of()), "incompatible_tags"),
                    Json.optionalInteger(Json.get(raw, "stop_index"), "stop_index"),
                    Json.get(raw, "value"),
                    Json.get(raw, "shape_type", "rigid_cuboid"),
                    hull(raw, unit),
                    // The solvers' ratio rule, applied once: floor(ratio * 1000000 + 0.5) on the double.
                    ratio == null ? null : ContainerCheck.ratioPpm(Units.ratioOf(ratio)),
                    Json.optionalInteger(Json.get(raw, "max_compression_pressure_kpa"), "max_compression_pressure_kpa"),
                    Json.get(raw, "stacking_group"),
                    Json.truthy(accepted) ? tagSet(accepted, "accepts_stacking_groups") : Set.of());
            items.set(id, spec);
        }
        return items;
    }

    static Ordered<ContainerSpec> containers(Object request, String unit) {
        Ordered<ContainerSpec> containers = new Ordered<>();
        for (Object raw : Json.items(Json.at(request, "containers"), "request.containers")) {
            Object id = Json.at(raw, "id");
            Object outer = Json.get(raw, "outer_dimensions");
            Object machine = Json.get(raw, "made_to_measure");
            boolean flatBag = Json.get(raw, "deformable_boundary") != null;
            ContainerSpec spec = new ContainerSpec(
                    id,
                    Units.dimensions(Json.at(raw, "inner_dimensions"), unit),
                    outer == null ? null : Units.dimensions(outer, unit),
                    Json.integer(Json.get(raw, "cost_minor", 0L), "cost_minor"),
                    measured(raw, "max_payload", "g", Units.WEIGHT),
                    Json.optionalInteger(Json.get(raw, "max_items"), "max_items"),
                    Json.optionalInteger(Json.get(raw, "quantity"), "quantity"),
                    obstacles(raw, unit),
                    tagSet(Json.get(raw, "tags", List.of()), "tags"),
                    tagLimits(raw),
                    Units.ratioOf(Json.get(raw, "void_fill_reserve_ratio", 0L)),
                    Units.toTicks(Json.get(raw, "tare_weight", 0L), "g", Units.WEIGHT),
                    axles(raw, unit),
                    measured(raw, "max_stack_density", "g", Units.WEIGHT),
                    Json.get(raw, "rate_table"),
                    accessDirections(raw),
                    flatBag,
                    flatBag || machine != null,
                    machine == null ? null : machine(machine, unit));
            containers.set(id, spec);
        }
        return containers;
    }

    /** {@code None if raw.get(key) is None else to_ticks(...)}. */
    private static BigInteger measured(Object raw, String key, String unit, Map<String, BigInteger> table) {
        Object value = Json.get(raw, key);
        return value == null ? null : Units.toTicks(value, unit, table);
    }

    /** {@code frozenset(value)} of a request's tag list; the contract's tags are strings. */
    private static Set<String> tagSet(Object value, String what) {
        Set<String> tags = new LinkedHashSet<>();
        for (Object tag : Json.items(value, what)) {
            tags.add(Json.text(tag, what));
        }
        return tags;
    }

    private static List<Vertex> hull(Object raw, String unit) {
        Object declared = Json.get(raw, "hull_vertices");
        if (declared == null) {
            return null;
        }
        List<Vertex> vertices = new ArrayList<>();
        for (Object vertex : Json.items(declared, "hull_vertices")) {
            vertices.add(new Vertex(Units.toTicks(Json.at(vertex, "x"), unit, Units.LENGTH),
                    Units.toTicks(Json.at(vertex, "y"), unit, Units.LENGTH),
                    Units.toTicks(Json.at(vertex, "z"), unit, Units.LENGTH)));
        }
        return vertices;
    }

    private static List<String> accessDirections(Object raw) {
        List<?> declared = Json.items(Json.get(raw, "access_directions", List.of()), "access_directions");
        declared.forEach(Json::hashKey);
        return EXIT_DIRECTIONS.stream().filter(direction -> declared.stream()
                .anyMatch(named -> Json.isText(named, direction))).toList();
    }

    private static List<Cuboid> obstacles(Object raw, String unit) {
        List<Cuboid> boxes = new ArrayList<>();
        for (Object obstacle : Json.items(Json.get(raw, "obstacles", List.of()), "obstacles")) {
            List<Object> components = new ArrayList<>();
            components.add(obstacle);
            components.addAll(Json.items(Json.get(obstacle, "additional_boxes", List.of()), "additional_boxes"));
            for (Object component : components) {
                Object origin = Json.get(component, "origin", Map.of());
                List<BigInteger> sides = Units.dimensions(Json.at(component, "dimensions"), unit);
                boxes.add(new Cuboid(Units.toTicks(Json.get(origin, "x", 0L), unit, Units.LENGTH),
                        Units.toTicks(Json.get(origin, "y", 0L), unit, Units.LENGTH),
                        Units.toTicks(Json.get(origin, "z", 0L), unit, Units.LENGTH),
                        sides.get(0), sides.get(1), sides.get(2)));
            }
        }
        return boxes;
    }

    /** {@code {str(tag): int(limit)}}, sorted by tag as the reference iterates it. */
    private static List<Map.Entry<String, BigInteger>> tagLimits(Object raw) {
        Object declared = Json.get(raw, "tag_limits", Map.of());
        if (!Json.isDict(declared)) {
            throw MalformedResult.shape("tag_limits must be an object");
        }
        List<Map.Entry<String, BigInteger>> limits = new ArrayList<>();
        for (Map.Entry<?, ?> entry : Json.dict(declared).entrySet()) {
            limits.add(Map.entry(String.valueOf(entry.getKey()), Json.pyInt(entry.getValue(), "tag_limits")));
        }
        limits.sort((left, right) -> Json.compare(left.getKey(), right.getKey()));
        return limits;
    }

    private static List<BigInteger[]> axles(Object raw, String unit) {
        Object declared = Json.get(raw, "axles");
        if (declared == null) {
            return null;
        }
        List<BigInteger[]> axles = new ArrayList<>();
        for (Object axle : Json.items(declared, "axles")) {
            axles.add(new BigInteger[] {Units.toTicks(Json.at(axle, "position"), unit, Units.LENGTH),
                measured(axle, "max_load", "g", Units.WEIGHT)});
        }
        return axles;
    }

    private static MachineSpec machine(Object raw, String unit) {
        Object price = Json.get(raw, "cost_per_square_metre_minor");
        Object tare = Json.get(raw, "tare_per_square_metre");
        return new MachineSpec(sides(raw, "minimum", unit), sides(raw, "increment", unit),
                Json.truthy(price) ? Json.integer(price, "cost_per_square_metre_minor") : BigInteger.ZERO,
                tare == null ? BigInteger.ZERO : Units.toTicks(tare, "g", Units.WEIGHT),
                sides(raw, "outer_allowance", unit));
    }

    private static List<BigInteger> sides(Object raw, String name, String unit) {
        Object value = Json.get(raw, name);
        return value == null ? List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO) : Units.dimensions(value, unit);
    }
}
