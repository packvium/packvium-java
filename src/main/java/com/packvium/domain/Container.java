package com.packvium.domain;

import com.packvium.unit.Weight;
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
        Optional<RateTable> rateTable) {

    public Container {
        obstacles = List.copyOf(obstacles);
        tags = List.copyOf(tags);
        metadata = Immutables.orderedMap(metadata);
        tagLimits = Immutables.orderedMap(tagLimits);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Container id is required");
        }
        if (quantity.isPresent() && quantity.get() <= 0) {
            throw new IllegalArgumentException("Container quantity must be positive");
        }
        if (costMinor < 0) {
            throw new IllegalArgumentException("Container cost cannot be negative");
        }
        if (voidFillReserveRatio < 0 || voidFillReserveRatio > 1) {
            throw new IllegalArgumentException("void_fill_reserve_ratio must be between 0 and 1");
        }
        if (tagLimits.values().stream().anyMatch(limit -> limit < 1)) {
            throw new IllegalArgumentException("tag_limits must be at least 1");
        }
        if (outerDimensions.isPresent() && !innerDimensions.fitsInside(outerDimensions.get())) {
            throw new IllegalArgumentException("Outer dimensions cannot be smaller than inner dimensions");
        }
        requireObstaclesInside(innerDimensions, obstacles);
        axles.ifPresent(pair -> requireAxlesInside(innerDimensions, pair));
    }

    public static Builder builder(String id, Dimensions innerDimensions) {
        return new Builder(id, innerDimensions);
    }

    private static void requireObstaclesInside(Dimensions inner, List<Obstacle> obstacles) {
        AxisAlignedBox boundary = new AxisAlignedBox(Point.ORIGIN, inner);
        for (Obstacle obstacle : obstacles) {
            for (AxisAlignedBox box : obstacle.boxes()) {
                if (!boundary.contains(box)) {
                    throw new IllegalArgumentException("Obstacle " + obstacle.id() + " lies outside container");
                }
            }
        }
    }

    private static void requireAxlesInside(Dimensions inner, AxlePair axles) {
        if (axles.rear().position().ticks() > inner.length().ticks()) {
            throw new IllegalArgumentException("Axle positions must lie within the container's length");
        }
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

        public Container build() {
            return new Container(id, innerDimensions, Optional.ofNullable(outerDimensions), tareWeight,
                    Optional.ofNullable(maxPayload), costMinor, Optional.ofNullable(quantity), obstacles, tags,
                    Optional.ofNullable(maxItems), metadata, voidFillReserveRatio, tagLimits,
                    Optional.ofNullable(maxStackDensity), Optional.ofNullable(axles),
                    Optional.ofNullable(rateTable));
        }
    }
}
