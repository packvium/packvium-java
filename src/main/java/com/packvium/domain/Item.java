package com.packvium.domain;

import com.packvium.unit.Length;
import com.packvium.unit.Weight;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Item(
        String id,
        Dimensions dimensions,
        Weight weight,
        long quantity,
        List<Rotation> allowedRotations,
        boolean keepUpright,
        boolean stackable,
        boolean mustBeOnFloor,
        Optional<Weight> maxTopLoad,
        double minimumSupportRatio,
        Optional<String> groundContactRule,
        Optional<String> group,
        List<String> tags,
        List<String> incompatibleTags,
        long priority,
        Map<String, Object> metadata,
        Optional<Long> maxStackedItems,
        List<String> eligibleContainerTags,
        Optional<Length> nestingHeight,
        Optional<Long> stopIndex,
        Optional<Long> value,
        ShapeType shapeType,
        Optional<List<Vertex>> hullVertices,
        Optional<Long> compressionRatioPpm,
        Optional<Long> maxCompressionPressureKpa) {

    /**
     * The largest {@code stop_index} every engine can carry identically. JavaScript holds
     * numbers as doubles, so two consecutive stops above 2^53 - 1 would become one number there.
     */
    public static final long MAX_EXACT_STOP_INDEX = 9_007_199_254_740_991L;

    public static final List<String> GROUND_CONTACT_RULES = List.of("free", "covered", "single", "multiple");

    public Item {
        tags = Immutables.distinct(tags);
        incompatibleTags = Immutables.distinct(incompatibleTags);
        eligibleContainerTags = Immutables.distinct(eligibleContainerTags);
        metadata = Immutables.orderedMap(metadata);
        requireScalarRules(id, quantity, minimumSupportRatio, maxStackedItems, groundContactRule);
        requireRouteAndValue(dimensions, nestingHeight, stopIndex, value);
        allowedRotations = admitRotations(allowedRotations, keepUpright);
        hullVertices = admitShape(shapeType, hullVertices, compressionRatioPpm, maxCompressionPressureKpa,
                dimensions, nestingHeight);
    }

    public static Builder builder(String id, Dimensions dimensions) {
        return new Builder(id, dimensions);
    }

    /**
     * Whether what rests on this item can change a verdict: it refuses load, or -- when
     * compressible -- yields to it.
     */
    public boolean isStackSensitive() {
        return !stackable || maxTopLoad.isPresent() || maxStackedItems.isPresent()
                || maxCompressionPressureKpa.isPresent();
    }

    public List<ItemInstance> instances() {
        List<ItemInstance> copies = new ArrayList<>();
        for (long sequence = 1; sequence <= quantity; sequence++) {
            copies.add(new ItemInstance(this, sequence));
        }
        return List.copyOf(copies);
    }

    private static void requireScalarRules(String id, long quantity, double minimumSupportRatio,
            Optional<Long> maxStackedItems, Optional<String> groundContactRule) {
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Item id is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Item quantity must be positive");
        }
        if (minimumSupportRatio < 0 || minimumSupportRatio > 1) {
            throw new IllegalArgumentException("Minimum support ratio must be between 0 and 1");
        }
        if (maxStackedItems.isPresent() && maxStackedItems.get() < 1) {
            throw new IllegalArgumentException("max_stacked_items must be at least 1");
        }
        if (groundContactRule.isPresent() && !GROUND_CONTACT_RULES.contains(groundContactRule.get())) {
            throw new IllegalArgumentException(
                    "ground_contact_rule must be one of " + String.join(", ", GROUND_CONTACT_RULES));
        }
    }

    private static void requireRouteAndValue(Dimensions dimensions, Optional<Length> nestingHeight,
            Optional<Long> stopIndex, Optional<Long> value) {
        if (nestingHeight.isPresent() && nestingHeight.get().ticks() >= dimensions.height().ticks()) {
            throw new IllegalArgumentException(
                    "nesting_height must be at least zero and strictly less than the item's own height");
        }
        if (stopIndex.isPresent() && (stopIndex.get() < 0 || stopIndex.get() > MAX_EXACT_STOP_INDEX)) {
            throw new IllegalArgumentException("stop_index must be a non-negative safe integer");
        }
        if (value.isPresent() && value.get() < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
    }

    private static List<Rotation> admitRotations(List<Rotation> allowed, boolean keepUpright) {
        List<Rotation> admitted = keepUpright
                ? allowed.stream().filter(Rotation.upright()::contains).toList()
                : List.copyOf(allowed);
        if (admitted.isEmpty()) {
            throw new IllegalArgumentException("At least one rotation must be allowed");
        }
        return admitted;
    }

    /**
     * Admit this item's shape, or refuse it with the reason. Each shape field belongs
     * to exactly one shape; one set against the wrong shape is refused rather than ignored,
     * because a compression_ratio silently dropped on a convex_hull reads as an item packed to
     * limits it never had.
     */
    private static Optional<List<Vertex>> admitShape(ShapeType shapeType, Optional<List<Vertex>> hullVertices,
            Optional<Long> ratioPpm, Optional<Long> limitKpa, Dimensions dimensions,
            Optional<Length> nestingHeight) {
        for (Map.Entry<String, Boolean> field : foreignFields(shapeType, hullVertices, ratioPpm, limitKpa).entrySet()) {
            if (field.getValue()) {
                throw new IllegalArgumentException(
                        field.getKey() + " is not part of a " + shapeType.wireName() + " item");
            }
        }
        // Both rewrite occupied height; choosing an order silently would give each engine its own
        // contract, so the interaction is refused until a task defines it.
        if (nestingHeight.isPresent() && shapeType != ShapeType.RIGID_CUBOID) {
            throw new IllegalArgumentException(
                    "nesting_height with shape_type " + shapeType.wireName() + " is not supported yet");
        }
        return switch (shapeType) {
            case CONVEX_HULL -> Optional.of(admitHull(hullVertices, dimensions));
            case COMPRESSIBLE -> {
                admitCompression(ratioPpm, limitKpa);
                yield hullVertices;
            }
            case RIGID_CUBOID -> hullVertices;
        };
    }

    private static Map<String, Boolean> foreignFields(ShapeType shapeType, Optional<List<Vertex>> hullVertices,
            Optional<Long> ratioPpm, Optional<Long> limitKpa) {
        Map<String, Boolean> foreign = new LinkedHashMap<>();
        if (shapeType != ShapeType.CONVEX_HULL) {
            foreign.put("hull_vertices", hullVertices.isPresent());
        }
        if (shapeType != ShapeType.COMPRESSIBLE) {
            foreign.put("compression_ratio", ratioPpm.isPresent());
            foreign.put("max_compression_pressure_kpa", limitKpa.isPresent());
        }
        return foreign;
    }

    private static List<Vertex> admitHull(Optional<List<Vertex>> hullVertices, Dimensions dimensions) {
        List<Vertex> vertices = HullShape.validate(hullVertices.orElseThrow(
                () -> new IllegalArgumentException("a convex_hull item requires hull_vertices")));
        Vertex[] extent = HullShape.boundingExtent(vertices);
        long[] declared = {dimensions.length().ticks(), dimensions.width().ticks(), dimensions.height().ticks()};
        for (int axis = 0; axis < 3; axis++) {
            // `dimensions` stays the broad phase and the candidate envelope, so a hull poking out
            // of it would be collision-tested against space the solver never reserved.
            if (extent[1].component(axis) - extent[0].component(axis) > declared[axis]) {
                throw new IllegalArgumentException("hull_vertices span does not fit inside dimensions");
            }
        }
        return vertices;
    }

    private static void admitCompression(Optional<Long> ratioPpm, Optional<Long> limitKpa) {
        if (ratioPpm.isEmpty() || limitKpa.isEmpty()) {
            throw new IllegalArgumentException(
                    "a compressible item requires both compression_ratio and max_compression_pressure_kpa");
        }
        if (ratioPpm.get() < 0 || ratioPpm.get() > Compression.PPM) {
            throw new IllegalArgumentException("compression_ratio must be between zero and one");
        }
        if (limitKpa.get() < 0) {
            throw new IllegalArgumentException("max_compression_pressure_kpa cannot be negative");
        }
    }

    public static final class Builder {
        private final String id;
        private final Dimensions dimensions;
        private Weight weight = Weight.ZERO;
        private long quantity = 1;
        private List<Rotation> allowedRotations = Rotation.all();
        private boolean keepUpright;
        private boolean stackable = true;
        private boolean mustBeOnFloor;
        private Weight maxTopLoad;
        private double minimumSupportRatio;
        private String groundContactRule;
        private String group;
        private List<String> tags = List.of();
        private List<String> incompatibleTags = List.of();
        private long priority;
        private Map<String, Object> metadata = Map.of();
        private Long maxStackedItems;
        private List<String> eligibleContainerTags = List.of();
        private Length nestingHeight;
        private Long stopIndex;
        private Long value;
        private ShapeType shapeType = ShapeType.RIGID_CUBOID;
        private List<Vertex> hullVertices;
        private Long compressionRatioPpm;
        private Long maxCompressionPressureKpa;

        private Builder(String id, Dimensions dimensions) {
            this.id = id;
            this.dimensions = dimensions;
        }

        public Builder weight(Weight value) {
            weight = value;
            return this;
        }

        public Builder quantity(long value) {
            quantity = value;
            return this;
        }

        public Builder allowedRotations(List<Rotation> value) {
            allowedRotations = value;
            return this;
        }

        public Builder keepUpright(boolean value) {
            keepUpright = value;
            return this;
        }

        public Builder stackable(boolean value) {
            stackable = value;
            return this;
        }

        public Builder mustBeOnFloor(boolean value) {
            mustBeOnFloor = value;
            return this;
        }

        public Builder maxTopLoad(Weight value) {
            maxTopLoad = value;
            return this;
        }

        public Builder minimumSupportRatio(double value) {
            minimumSupportRatio = value;
            return this;
        }

        public Builder groundContactRule(String value) {
            groundContactRule = value;
            return this;
        }

        public Builder group(String value) {
            group = value;
            return this;
        }

        public Builder tags(List<String> value) {
            tags = value;
            return this;
        }

        public Builder incompatibleTags(List<String> value) {
            incompatibleTags = value;
            return this;
        }

        public Builder priority(long value) {
            priority = value;
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            metadata = value;
            return this;
        }

        public Builder maxStackedItems(Long value) {
            maxStackedItems = value;
            return this;
        }

        public Builder eligibleContainerTags(List<String> value) {
            eligibleContainerTags = value;
            return this;
        }

        public Builder nestingHeight(Length value) {
            nestingHeight = value;
            return this;
        }

        public Builder stopIndex(Long value) {
            stopIndex = value;
            return this;
        }

        public Builder value(Long worth) {
            value = worth;
            return this;
        }

        public Builder shapeType(ShapeType value) {
            shapeType = value;
            return this;
        }

        public Builder hullVertices(List<Vertex> value) {
            hullVertices = value;
            return this;
        }

        public Builder compressionRatioPpm(Long value) {
            compressionRatioPpm = value;
            return this;
        }

        public Builder maxCompressionPressureKpa(Long value) {
            maxCompressionPressureKpa = value;
            return this;
        }

        public Item build() {
            return new Item(id, dimensions, weight, quantity, allowedRotations, keepUpright, stackable,
                    mustBeOnFloor, Optional.ofNullable(maxTopLoad), minimumSupportRatio,
                    Optional.ofNullable(groundContactRule), Optional.ofNullable(group), tags, incompatibleTags,
                    priority, metadata, Optional.ofNullable(maxStackedItems), eligibleContainerTags,
                    Optional.ofNullable(nestingHeight), Optional.ofNullable(stopIndex), Optional.ofNullable(value),
                    shapeType, Optional.ofNullable(hullVertices), Optional.ofNullable(compressionRatioPpm),
                    Optional.ofNullable(maxCompressionPressureKpa));
        }
    }
}
