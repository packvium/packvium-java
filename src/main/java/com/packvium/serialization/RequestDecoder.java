package com.packvium.serialization;

import com.packvium.config.EffortBudget;
import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.Axle;
import com.packvium.domain.AxlePair;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.Compression;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.Item;
import com.packvium.domain.Obstacle;
import com.packvium.domain.Point;
import com.packvium.domain.RateTable;
import com.packvium.domain.Rotation;
import com.packvium.domain.ShapeType;
import com.packvium.domain.Vertex;
import com.packvium.unit.Length;
import com.packvium.unit.Rounding;
import com.packvium.unit.Weight;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a wire request into the domain, in the reference engines' order, so the first
 * refusal a malformed request earns is the same one they give.
 */
public final class RequestDecoder {
    private static final String WEIGHT_UNIT = "g";
    private static final long QUALITY_PLAN_NODE_LIMIT = 100_000;
    private static final long QUALITY_BREADTH = 16;

    private final UnsupportedFields unsupported;

    public RequestDecoder(UnsupportedFields unsupported) {
        this.unsupported = unsupported;
    }

    public DecodedRequest decode(Object raw) {
        Fields request = new Fields(raw, "request");
        unsupported.reject((Map<?, ?>) raw);
        String unit = request.child("units").text("length", "mm");
        PackingConfig config = config(request.child("configuration"), unit);
        List<Item> items = new ArrayList<>();
        for (Object entry : listOf(request.required("items"), "items")) {
            items.add(item(new Fields(entry, "item"), unit));
        }
        List<Container> containers = new ArrayList<>();
        for (Object entry : listOf(request.required("containers"), "containers")) {
            containers.add(container(new Fields(entry, "container"), unit));
        }
        Fields output = request.child("output");
        return new DecodedRequest(config, items, containers, output.text("length_unit", unit),
                output.text("weight_unit", WEIGHT_UNIT), CatalogVersions.read(request.raw("catalog_versions_used")),
                Optional.ofNullable(request.raw("policy")));
    }

    private static PackingConfig config(Fields config, String unit) {
        SolverProfile profile = SolverProfile.fromWireName(config.text("solver_profile", "balanced"));
        boolean quality = profile == SolverProfile.QUALITY;
        return PackingConfig.builder(profile)
                .timeLimitMs(config.integer("time_limit_ms", 1000))
                .topK(config.integer("alternatives", 3))
                .seed(config.integer("seed", 42))
                .maxContainers(config.optionalInteger("max_containers"))
                .clearance(Length.parse(config.has("clearance") ? config.raw("clearance") : 0L, unit))
                .minimumSupportRatio(config.number("minimum_support_ratio", 0))
                .exactItemLimit(config.integer("exact_item_limit", 7))
                .multiStartOrders(config.integer("multi_start_orders", 8))
                .maxCandidatesPerItem(config.integer("max_candidates_per_item", quality ? QUALITY_BREADTH : 1))
                .maxCandidatePoints(config.integer("max_candidate_points", 4096))
                .solvers(config.strings("solvers"))
                .objective(config.text("objective", "default"))
                .effortBudget(config.has("effort_budget") ? effortBudget(config.child("effort_budget")) : null)
                .dimensionalWeightDivisor(config.optionalInteger("dimensional_weight_divisor"))
                .dimensionalWeightLengthUnit(config.text("dimensional_weight_length_unit", "in"))
                .dimensionalWeightWeightUnit(config.text("dimensional_weight_weight_unit", "lb"))
                .requirePlacementCoordinates(config.bool("require_placement_coordinates", true))
                .containerPlanBeamWidth(config.integer("container_plan_beam_width", quality ? QUALITY_BREADTH : 1))
                .containerPlanNodeLimit(config.integer("container_plan_node_limit",
                        quality ? QUALITY_PLAN_NODE_LIMIT : 1))
                .build();
    }

    private static EffortBudget effortBudget(Fields budget) {
        return new EffortBudget(
                Optional.ofNullable(budget.optionalInteger("max_candidates_evaluated")),
                Optional.ofNullable(budget.optionalInteger("max_placement_attempts")),
                Optional.ofNullable(budget.optionalInteger("max_search_nodes")),
                Optional.ofNullable(budget.optionalInteger("max_restarts")));
    }

    private static Item item(Fields item, String unit) {
        Item.Builder builder = Item.builder(item.text("id", ""), dimensions(item.required("dimensions"), unit))
                .weight(weight(item, "weight", Weight.ZERO))
                .quantity(item.integer("quantity", 1))
                .allowedRotations(rotations(item))
                .keepUpright(item.bool("keep_upright", false))
                .stackable(item.bool("stackable", true))
                .mustBeOnFloor(item.bool("must_be_on_floor", false))
                .maxTopLoad(weight(item, "max_top_load", null))
                .minimumSupportRatio(item.number("minimum_support_ratio", 0))
                .group(item.optionalText("group").orElse(null))
                .tags(item.strings("tags"))
                .incompatibleTags(item.strings("incompatible_tags"))
                .priority(item.integer("priority", 0))
                .metadata(item.object("metadata"))
                .maxStackedItems(item.optionalInteger("max_stacked_items"))
                .eligibleContainerTags(item.strings("eligible_container_tags"))
                .groundContactRule(item.optionalText("ground_contact_rule").orElse(null))
                .nestingHeight(item.has("nesting_height") ? Length.parse(item.raw("nesting_height"), unit) : null)
                .stopIndex(item.optionalInteger("stop_index"))
                .value(item.optionalInteger("value"))
                .shapeType(ShapeType.fromWireName(item.text("shape_type", ShapeType.RIGID_CUBOID.wireName())))
                .hullVertices(item.has("hull_vertices") ? hullVertices(item.list("hull_vertices"), unit) : null)
                .compressionRatioPpm(item.has("compression_ratio")
                        ? ratioToPpm(item.number("compression_ratio", 0)) : null)
                .maxCompressionPressureKpa(item.optionalInteger("max_compression_pressure_kpa"));
        return builder.build();
    }

    private static List<Rotation> rotations(Fields item) {
        if (!item.has("allowed_rotations")) {
            return Rotation.all();
        }
        return item.strings("allowed_rotations").stream().map(RequestDecoder::rotation).toList();
    }

    private static Rotation rotation(String name) {
        for (Rotation rotation : Rotation.values()) {
            if (rotation.name().equals(name)) {
                return rotation;
            }
        }
        throw new IllegalArgumentException("\"" + name + "\" is not a valid backing value for enum Rotation");
    }

    /**
     * Wire hull vertices are non-negative offsets from the corner of the item's own box:
     * they go through {@link Length}, which refuses a negative value, so engines cannot
     * disagree about where an item's frame starts.
     */
    private static List<Vertex> hullVertices(List<?> raw, String unit) {
        List<Vertex> vertices = new ArrayList<>();
        for (Object entry : raw) {
            Fields vertex = new Fields(entry, "hull_vertices");
            vertices.add(new Vertex(
                    Length.parse(vertex.raw("x"), unit).ticks(),
                    Length.parse(vertex.raw("y"), unit).ticks(),
                    Length.parse(vertex.raw("z"), unit).ticks()));
        }
        return vertices;
    }

    /** The published rule, floor(ratio * 1000000 + 0.5), applied once at the boundary. */
    private static long ratioToPpm(double ratio) {
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("compression_ratio must be between zero and one");
        }
        return (long) (ratio * Compression.PPM + 0.5);
    }

    private static Container container(Fields container, String unit) {
        return Container.builder(container.text("id", ""),
                        dimensions(container.required("inner_dimensions"), unit))
                .outerDimensions(container.has("outer_dimensions")
                        ? dimensions(container.raw("outer_dimensions"), unit) : null)
                .tareWeight(weight(container, "tare_weight", Weight.ZERO))
                .maxPayload(weight(container, "max_payload", null))
                .costMinor(container.integer("cost_minor", 0))
                .quantity(container.optionalInteger("quantity"))
                .obstacles(obstacles(container.list("obstacles"), unit))
                .tags(container.strings("tags"))
                .maxItems(container.optionalInteger("max_items"))
                .metadata(container.object("metadata"))
                .voidFillReserveRatio(container.number("void_fill_reserve_ratio", 0))
                .tagLimits(tagLimits(container.child("tag_limits")))
                .maxStackDensity(weight(container, "max_stack_density", null))
                .axles(container.has("axles") ? axles(container.list("axles"), unit) : null)
                .rateTable(container.has("rate_table") ? rateTable(container.child("rate_table")) : null)
                .build();
    }

    private static List<Obstacle> obstacles(List<?> raw, String unit) {
        List<Obstacle> obstacles = new ArrayList<>();
        for (Object entry : raw) {
            Fields obstacle = new Fields(entry, "obstacle");
            List<AxisAlignedBox> additional = new ArrayList<>();
            for (Object box : obstacle.list("additional_boxes")) {
                additional.add(box(new Fields(box, "additional_box"), unit));
            }
            obstacles.add(new Obstacle(obstacle.text("id", ""), box(obstacle, unit), additional));
        }
        return obstacles;
    }

    private static AxisAlignedBox box(Fields box, String unit) {
        Fields origin = box.child("origin");
        Point corner = new Point(coordinate(origin, "x", unit), coordinate(origin, "y", unit),
                coordinate(origin, "z", unit));
        return new AxisAlignedBox(corner, dimensions(box.required("dimensions"), unit));
    }

    private static long coordinate(Fields origin, String axis, String unit) {
        return Length.parse(origin.has(axis) ? origin.raw(axis) : 0L, unit).ticks();
    }

    private static Map<String, Long> tagLimits(Fields limits) {
        Map<String, Long> typed = new LinkedHashMap<>();
        for (Object key : limits.members().keySet()) {
            String tag = key.toString();
            typed.put(tag, limits.integer(tag, 0));
        }
        return typed;
    }

    private static AxlePair axles(List<?> raw, String unit) {
        if (raw.size() != 2) {
            throw new IllegalArgumentException("axles must list exactly the front and the rear axle");
        }
        return new AxlePair(axle(new Fields(raw.get(0), "axle"), unit), axle(new Fields(raw.get(1), "axle"), unit));
    }

    private static Axle axle(Fields axle, String unit) {
        return new Axle(Length.parse(axle.raw("position"), unit),
                Optional.ofNullable(weight(axle, "max_load", null)));
    }

    private static RateTable rateTable(Fields table) {
        return new RateTable(integers(table.list("weight_brackets_g")), integers(table.list("prices_minor")),
                table.integer("minimum_charge_minor", 0), table.integer("fuel_surcharge_permille", 0));
    }

    private static List<Long> integers(List<?> raw) {
        List<Long> values = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Long whole)) {
                throw new IllegalArgumentException("rate_table values must be integers");
            }
            values.add(whole);
        }
        return values;
    }

    private static Dimensions dimensions(Object raw, String unit) {
        return Dimensions.fromMap(new Fields(raw, "dimensions").members(), unit, Rounding.NEAREST);
    }

    private static Weight weight(Fields fields, String name, Weight fallback) {
        return fields.has(name) ? Weight.parse(fields.raw(name), WEIGHT_UNIT) : fallback;
    }

    private static List<?> listOf(Object value, String name) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return list;
    }
}
