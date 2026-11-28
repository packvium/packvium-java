package com.packvium.domain;

import com.packvium.support.ObjectRefusal;
import com.packvium.support.PythonRepr;
import com.packvium.unit.Weight;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Container(
        String id,
        Dimensions innerDimensions,
        Optional<Dimensions> outerDimensions,
        Weight tareWeight,
        Optional<Weight> maxPayload,
        long costMinor,
        Optional<Long> quantity,
        List<Obstacle> obstacles,
        List<String> tags,
        Optional<Long> maxItems,
        Map<String, Object> metadata,
        double voidFillReserveRatio,
        Map<String, Long> tagLimits,
        Optional<Weight> maxStackDensity,
        Optional<AxlePair> axles,
        Optional<RateTable> rateTable,
        List<String> accessDirections,
        List<Placement> preloaded,
        Optional<String> deformableBoundary,
        Optional<MadeToMeasure> madeToMeasure) {

    /**
     * {@code accessDirections} are the walls this container can be unloaded through.
     * Empty means the horizontal half of route order is not enforced for it -- not that it is
     * sealed: defaulting to all six would be a different nearly-nothing and would change answers
     * for every caller who never set the field. Canonicalised here because every construction
     * path passes through this constructor.
     *
     * <p>{@code preloaded}: items already in this one container instance before search starts.
     * Set only by the orchestrator, on the instance a request's {@code fixed_placements} name, so
     * every solver's {@code ContainerState} starts from them without knowing they exist; a packed
     * result carries the container with this emptied again.
     *
     * <p>{@code deformableBoundary}: {@code "flat_bag"} makes this a flexible bag whose package
     * conforms to its contents ({@link FlatBag}). {@code madeToMeasure}: cut to measure by
     * an on-demand box machine whose largest box is {@code innerDimensions}; a packed
     * result carries the fabricated box instead.
     */
    public Container {
        preloaded = List.copyOf(preloaded);
        obstacles = List.copyOf(obstacles);
        tags = List.copyOf(tags);
        metadata = Immutables.orderedMap(metadata);
        tagLimits = Immutables.orderedMap(tagLimits);
        if (id.isEmpty()) {
            throw new ObjectRefusal("invalid_value", "container id is required");
        }
        if (deformableBoundary.isPresent()) {
            requireBagFields(deformableBoundary.get(), obstacles, axles, accessDirections, maxStackDensity,
                    outerDimensions, voidFillReserveRatio);
        }
        madeToMeasure.ifPresent(machine -> machine.admits(Sides.of(innerDimensions)));
        if (quantity.isPresent() && quantity.get() <= 0) {
            throw new ObjectRefusal("below_minimum", "container quantity must be positive");
        }
        if (costMinor < 0) {
            throw new ObjectRefusal("below_minimum", "container cost cannot be negative");
        }
        if (maxItems.isPresent() && maxItems.get() < 1) {
            throw new ObjectRefusal("below_minimum", "container max_items must be at least 1");
        }
        if (!ObjectRefusal.isRatio(voidFillReserveRatio)) {
            throw ObjectRefusal.ratio(voidFillReserveRatio, "void_fill_reserve_ratio must be between 0 and 1");
        }
        if (tagLimits.values().stream().anyMatch(limit -> limit < 1)) {
            throw new ObjectRefusal("below_minimum", "tag_limits must be at least 1");
        }
        if (outerDimensions.isPresent() && !innerDimensions.fitsInside(outerDimensions.get())) {
            throw new ObjectRefusal("invalid_value", "outer dimensions cannot be smaller than inner dimensions");
        }
        requireObstaclesInside(innerDimensions, obstacles);
        axles.ifPresent(pair -> requireAxlesInside(innerDimensions, pair));
        accessDirections = canonicalDirections(accessDirections);
    }

    /** The same container holding {@code placements} before search. */
    public Container withPreloaded(List<Placement> placements) {
        return new Container(id, innerDimensions, outerDimensions, tareWeight, maxPayload, costMinor, quantity,
                obstacles, tags, maxItems, metadata, voidFillReserveRatio, tagLimits, maxStackDensity, axles,
                rateTable, accessDirections, placements, deformableBoundary, madeToMeasure);
    }

    public boolean isFlatBag() {
        return deformableBoundary.isPresent();
    }

    /** The declared container solved inside {@code rung}: same id, everything else unchanged. */
    public Container withEnvelope(Sides rung) {
        return resized(rung.toDimensions(), outerDimensions, costMinor, tareWeight);
    }

    /**
     * The box cut for an achieved extent: equal to this declared machine except its size, outer
     * size, price and tare. O(1). Containment is a theorem, not a check: every placement lies
     * inside {@code achieved}, and the grid never rounds below it or past the maximum.
     */
    public Container fabricate(Sides achieved) {
        MadeToMeasure machine = madeToMeasure.orElseThrow(
                () -> new IllegalStateException("container " + id + " is not made to measure"));
        long[] sides = new long[3];
        long[] outer = new long[3];
        for (int axis = 0; axis < 3; axis++) {
            sides[axis] = machine.grid(axis, achieved.get(axis));
            if (sides[axis] > Sides.of(innerDimensions).get(axis)) {
                throw new IllegalStateException("fabricated box exceeds the machine");
            }
            outer[axis] = Math.addExact(sides[axis], machine.outerAllowance().get(axis));
        }
        Sides box = new Sides(sides[0], sides[1], sides[2]);
        return resized(box.toDimensions(), Optional.of(Dimensions.ofTicks(outer[0], outer[1], outer[2])),
                machine.boxCostMinor(costMinor, box), new Weight(machine.boxTareTicks(tareWeight.ticks(), box)));
    }

    private Container resized(Dimensions inner, Optional<Dimensions> outer, long cost, Weight tare) {
        return new Container(id, inner, outer, tare, maxPayload, cost, quantity, obstacles, tags, maxItems, metadata,
                voidFillReserveRatio, tagLimits, maxStackDensity, axles, rateTable, accessDirections, preloaded,
                deformableBoundary, madeToMeasure);
    }

    /**
     * A bag has no walls, floor plan or chassis for a wall-anchored field to refer to, so those
     * fields are refused rather than read against a frame that moves with its contents.
     */
    private static void requireBagFields(String kind, List<Obstacle> obstacles, Optional<AxlePair> axles,
            List<String> accessDirections, Optional<Weight> maxStackDensity, Optional<Dimensions> outerDimensions,
            double voidFillReserveRatio) {
        if (!FlatBag.KINDS.contains(kind)) {
            throw new ObjectRefusal("not_allowed", "unknown deformable_boundary kind " + PythonRepr.string(kind));
        }
        Map<String, Boolean> present = new LinkedHashMap<>();
        present.put("obstacles", !obstacles.isEmpty());
        present.put("axles", axles.isPresent());
        present.put("access_directions", !accessDirections.isEmpty());
        present.put("max_stack_density", maxStackDensity.isPresent());
        present.put("outer_dimensions", outerDimensions.isPresent());
        present.put("void_fill_reserve_ratio", voidFillReserveRatio > 0);
        for (Map.Entry<String, Boolean> field : present.entrySet()) {
            if (field.getValue()) {
                throw new ObjectRefusal("not_allowed", field.getKey() + " cannot be combined with deformable_boundary");
            }
        }
    }

    public static Builder builder(String id, Dimensions innerDimensions) {
        return new Builder(id, innerDimensions);
    }

    private static void requireObstaclesInside(Dimensions inner, List<Obstacle> obstacles) {
        AxisAlignedBox boundary = new AxisAlignedBox(Point.ORIGIN, inner);
        for (Obstacle obstacle : obstacles) {
            for (AxisAlignedBox box : obstacle.boxes()) {
                if (!boundary.contains(box)) {
                    throw new ObjectRefusal("invalid_value", "obstacle " + obstacle.id() + " lies outside container");
                }
            }
        }
    }

    private static void requireAxlesInside(Dimensions inner, AxlePair axles) {
        if (axles.rear().position().ticks() > inner.length().ticks()) {
            throw new ObjectRefusal("invalid_value", "axle positions must lie within the container's length");
        }
    }

    /**
     * Deduplicated into the canonical order rather than kept as given: two callers naming the
     * same doors in a different order must search identically.
     */
    private static List<String> canonicalDirections(List<String> requested) {
        for (String direction : requested) {
            if (!SweptRegion.ALL_DIRECTIONS.contains(direction)) {
                throw new IllegalArgumentException("unknown movement direction " + direction);
            }
        }
        return SweptRegion.ALL_DIRECTIONS.stream().filter(requested::contains).toList();
    }

    public static final class Builder {
        private final String id;
        private final Dimensions innerDimensions;
        private Dimensions outerDimensions;
        private Weight tareWeight = Weight.ZERO;
        private Weight maxPayload;
        private long costMinor;
        private Long quantity;
        private List<Obstacle> obstacles = List.of();
        private List<String> tags = List.of();
        private Long maxItems;
        private Map<String, Object> metadata = Map.of();
        private double voidFillReserveRatio;
        private Map<String, Long> tagLimits = Map.of();
        private Weight maxStackDensity;
        private AxlePair axles;
        private RateTable rateTable;
        private List<String> accessDirections = List.of();
        private String deformableBoundary;
        private MadeToMeasure madeToMeasure;

        private Builder(String id, Dimensions innerDimensions) {
            this.id = id;
            this.innerDimensions = innerDimensions;
        }

        public Builder outerDimensions(Dimensions value) {
            outerDimensions = value;
            return this;
        }

        public Builder tareWeight(Weight value) {
            tareWeight = value;
            return this;
        }

        public Builder maxPayload(Weight value) {
            maxPayload = value;
            return this;
        }

        public Builder costMinor(long value) {
            costMinor = value;
            return this;
        }

        public Builder quantity(Long value) {
            quantity = value;
            return this;
        }

        public Builder obstacles(List<Obstacle> value) {
            obstacles = value;
            return this;
        }

        public Builder tags(List<String> value) {
            tags = value;
            return this;
        }

        public Builder maxItems(Long value) {
            maxItems = value;
            return this;
        }

        public Builder metadata(Map<String, Object> value) {
            metadata = value;
            return this;
        }

        public Builder voidFillReserveRatio(double value) {
            voidFillReserveRatio = value;
            return this;
        }

        public Builder tagLimits(Map<String, Long> value) {
            tagLimits = value;
            return this;
        }

        public Builder maxStackDensity(Weight value) {
            maxStackDensity = value;
            return this;
        }

        public Builder axles(AxlePair value) {
            axles = value;
            return this;
        }

        public Builder rateTable(RateTable value) {
            rateTable = value;
            return this;
        }

        public Builder accessDirections(List<String> value) {
            accessDirections = value;
            return this;
        }

        public Builder deformableBoundary(String kind) {
            deformableBoundary = kind;
            return this;
        }

        public Builder madeToMeasure(MadeToMeasure value) {
            madeToMeasure = value;
            return this;
        }

        public Container build() {
            return new Container(id, innerDimensions, Optional.ofNullable(outerDimensions), tareWeight,
                    Optional.ofNullable(maxPayload), costMinor, Optional.ofNullable(quantity), obstacles, tags,
                    Optional.ofNullable(maxItems), metadata, voidFillReserveRatio, tagLimits,
                    Optional.ofNullable(maxStackDensity), Optional.ofNullable(axles),
                    Optional.ofNullable(rateTable), accessDirections, List.of(), Optional.ofNullable(deformableBoundary),
                    Optional.ofNullable(madeToMeasure));
        }
    }
}
