package com.packvium.config;

import com.packvium.unit.Length;
import java.util.List;
import java.util.Optional;

public record PackingConfig(
        SolverProfile profile,
        long timeLimitMs,
        long topK,
        long seed,
        Optional<Long> maxContainers,
        Length clearance,
        double minimumSupportRatio,
        long exactItemLimit,
        long multiStartOrders,
        boolean validateResult,
        long maxCandidatesPerItem,
        long maxCandidatePoints,
        List<String> solvers,
        String objective,
        Optional<EffortBudget> effortBudget,
        Optional<Long> dimensionalWeightDivisor,
        String dimensionalWeightLengthUnit,
        String dimensionalWeightWeightUnit,
        boolean requirePlacementCoordinates,
        long containerPlanBeamWidth,
        long containerPlanNodeLimit) {

    public PackingConfig {
        solvers = List.copyOf(solvers);
        if (timeLimitMs <= 0 || topK <= 0 || exactItemLimit <= 0 || multiStartOrders <= 0
                || maxCandidatesPerItem <= 0 || containerPlanBeamWidth <= 0 || containerPlanNodeLimit <= 0) {
            throw new IllegalArgumentException("Positive configuration values required");
        }
        if (maxCandidatePoints < 16) {
            throw new IllegalArgumentException("maxCandidatePoints must be at least 16");
        }
        if (minimumSupportRatio < 0 || minimumSupportRatio > 1) {
            throw new IllegalArgumentException("Minimum support ratio must be between 0 and 1");
        }
        if (dimensionalWeightDivisor.isPresent() && dimensionalWeightDivisor.get() <= 0) {
            throw new IllegalArgumentException("dimensionalWeightDivisor must be positive");
        }
    }

    public static Builder builder(SolverProfile profile) {
        return new Builder(profile);
    }

    public static PackingConfig fast(long timeLimitMs) {
        return builder(SolverProfile.FAST).timeLimitMs(timeLimitMs).topK(1).multiStartOrders(1).build();
    }

    public static PackingConfig balanced() {
        return builder(SolverProfile.BALANCED).build();
    }

    public static PackingConfig quality() {
        return builder(SolverProfile.QUALITY).timeLimitMs(5000).topK(5).multiStartOrders(24)
                .maxCandidatesPerItem(16).containerPlanBeamWidth(16).containerPlanNodeLimit(100_000).build();
    }

    public static PackingConfig exactSmall() {
        return builder(SolverProfile.EXACT_SMALL).timeLimitMs(10_000).build();
    }

    public static final class Builder {
        private final SolverProfile profile;
        private long timeLimitMs = 1000;
        private long topK = 3;
        private long seed = 42;
        private Long maxContainers;
        private Length clearance = Length.ZERO;
        private double minimumSupportRatio;
        private long exactItemLimit = 7;
        private long multiStartOrders = 8;
        private boolean validateResult = true;
        private long maxCandidatesPerItem = 1;
        private long maxCandidatePoints = 4096;
        private List<String> solvers = List.of();
        private String objective = "default";
        private EffortBudget effortBudget;
        private Long dimensionalWeightDivisor;
        private String dimensionalWeightLengthUnit = "in";
        private String dimensionalWeightWeightUnit = "lb";
        private boolean requirePlacementCoordinates = true;
        private long containerPlanBeamWidth = 1;
        private long containerPlanNodeLimit = 1;

        private Builder(SolverProfile profile) {
            this.profile = profile;
        }

        public Builder timeLimitMs(long value) {
            timeLimitMs = value;
            return this;
        }

        public Builder topK(long value) {
            topK = value;
            return this;
        }

        public Builder seed(long value) {
            seed = value;
            return this;
        }

        public Builder maxContainers(Long value) {
            maxContainers = value;
            return this;
        }

        public Builder clearance(Length value) {
            clearance = value;
            return this;
        }

        public Builder minimumSupportRatio(double value) {
            minimumSupportRatio = value;
            return this;
        }

        public Builder exactItemLimit(long value) {
            exactItemLimit = value;
            return this;
        }

        public Builder multiStartOrders(long value) {
            multiStartOrders = value;
            return this;
        }

        public Builder validateResult(boolean value) {
            validateResult = value;
            return this;
        }

        public Builder maxCandidatesPerItem(long value) {
            maxCandidatesPerItem = value;
            return this;
        }

        public Builder maxCandidatePoints(long value) {
            maxCandidatePoints = value;
            return this;
        }

        public Builder solvers(List<String> value) {
            solvers = value;
            return this;
        }

        public Builder objective(String value) {
            objective = value;
            return this;
        }

        public Builder effortBudget(EffortBudget value) {
            effortBudget = value;
            return this;
        }

        public Builder dimensionalWeightDivisor(Long value) {
            dimensionalWeightDivisor = value;
            return this;
        }

        public Builder dimensionalWeightLengthUnit(String value) {
            dimensionalWeightLengthUnit = value;
            return this;
        }

        public Builder dimensionalWeightWeightUnit(String value) {
            dimensionalWeightWeightUnit = value;
            return this;
        }

        public Builder requirePlacementCoordinates(boolean value) {
            requirePlacementCoordinates = value;
            return this;
        }

        public Builder containerPlanBeamWidth(long value) {
            containerPlanBeamWidth = value;
            return this;
        }

        public Builder containerPlanNodeLimit(long value) {
            containerPlanNodeLimit = value;
            return this;
        }

        public PackingConfig build() {
            return new PackingConfig(profile, timeLimitMs, topK, seed, Optional.ofNullable(maxContainers), clearance,
                    minimumSupportRatio, exactItemLimit, multiStartOrders, validateResult, maxCandidatesPerItem,
                    maxCandidatePoints, solvers, objective, Optional.ofNullable(effortBudget),
                    Optional.ofNullable(dimensionalWeightDivisor), dimensionalWeightLengthUnit,
                    dimensionalWeightWeightUnit, requirePlacementCoordinates, containerPlanBeamWidth,
                    containerPlanNodeLimit);
        }
    }
}
