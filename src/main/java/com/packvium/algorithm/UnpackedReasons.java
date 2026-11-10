package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.ProvenRejection;
import com.packvium.constraint.Violation;
import com.packvium.constraint.SupportConstraint;
import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.OrientedDimensions;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.Rotation;
import com.packvium.domain.UnpackedItem;
import com.packvium.extension.CandidateScorer;
import java.util.List;
import java.util.Optional;

/** Why an item ended up unpacked, proven where pure geometry can prove it. */
final class UnpackedReasons {
    private final List<PlacementConstraint> customConstraints;
    private final CandidateScorer scorer;

    UnpackedReasons(List<PlacementConstraint> customConstraints, CandidateScorer scorer) {
        this.customConstraints = customConstraints;
        this.scorer = scorer;
    }

    UnpackedItem explain(ItemInstance item, List<Container> containers, boolean reached, List<PackedContainer> packed,
            PackingConfig config) {
        Reason reason = reason(item, containers, reached);
        String code = reason.code().equals("no_feasible_placement") && supportIsTheBlocker(item, packed, config)
                ? "insufficient_support"
                : reason.code();
        return new UnpackedItem(item, code, reason.details());
    }

    record Reason(String code, List<String> details) {
    }

    /**
     * Structural proofs first -- they hold whatever the search did, even past the deadline --
     * then what a registered constraint can prove, then what the search observed. O(c * r).
     */
    Reason reason(ItemInstance item, List<Container> containers, boolean reached) {
        if (!fitsSomewhere(item, containers, Rotation.all())) {
            return new Reason("no_compatible_container_dimensions", List.of());
        }
        // Distinguishes "too big in any rotation" from "only this rotation restriction rules every
        // container out"; both are pure geometry.
        if (!fitsSomewhere(item, containers, item.item().allowedRotations())) {
            return new Reason("rotation_restricted", List.of());
        }
        boolean weight = containers.stream().anyMatch(container -> container.maxPayload().isEmpty()
                || item.weight().ticks() <= container.maxPayload().get().ticks());
        if (!weight) {
            return new Reason("payload_exceeded", List.of());
        }
        boolean eligible = item.item().eligibleContainerTags().isEmpty() || containers.stream()
                .anyMatch(container -> item.item().eligibleContainerTags().stream().anyMatch(container.tags()::contains));
        if (!eligible) {
            return new Reason("no_eligible_container", List.of());
        }
        // Ranked after the geometric proofs: an impossible request is impossible whatever a
        // policy says, and naming the policy first would send a caller to fix the wrong thing.
        Optional<Reason> proven = provenByConstraint(item, containers);
        if (proven.isPresent()) {
            return proven.get();
        }
        if (reached) {
            return new Reason("time_limit", List.of());
        }
        if (item.item().group().isPresent()) {
            return new Reason("group_cannot_fit_together", List.of());
        }
        return new Reason("no_feasible_placement", List.of());
    }

    private Optional<Reason> provenByConstraint(ItemInstance item, List<Container> containers) {
        for (PlacementConstraint constraint : customConstraints) {
            if (constraint instanceof ProvenRejection proving) {
                Optional<Violation> proof = proving.provesUnplaceable(item, containers);
                if (proof.isPresent()) {
                    return Optional.of(new Reason(proof.get().code(), List.of(proof.get().detail())));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean fitsSomewhere(ItemInstance item, List<Container> containers, List<Rotation> rotations) {
        for (Container container : containers) {
            for (OrientedDimensions oriented : item.dimensions().uniqueRotations(rotations)) {
                if (oriented.dimensions().fitsInside(container.innerDimensions())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the item would fit in an already-packed container's final layout once support alone
     * is set aside, but not with it. An observed fact about this layout, not a proof
     * about every layout, run on its own small fixed budget so it can never hang the solve.
     */
    private boolean supportIsTheBlocker(ItemInstance item, List<PackedContainer> packed, PackingConfig config) {
        List<PlacementConstraint> constraints = ConstraintSet.defaults(config.minimumSupportRatio(), customConstraints,
                List.of());
        boolean canReject = constraints.stream()
                .anyMatch(constraint -> constraint instanceof SupportConstraint support && support.canReject(item));
        if (!canReject) {
            return false;
        }
        List<PlacementConstraint> withoutSupport = constraints.stream()
                .filter(constraint -> !(constraint instanceof SupportConstraint)).toList();
        Deadline budget = Deadline.ofMilliseconds(2_000);
        for (PackedContainer packedContainer : packed) {
            if (budget.expired()) {
                return false;
            }
            ContainerState state = new ContainerState(packedContainer.container(), packedContainer.sequence());
            for (Placement placement : packedContainer.placements()) {
                state.add(placement);
            }
            if (CandidateFinder.find(state, item, config, withoutSupport, new SearchStats(), budget, scorer, 1)
                    .isEmpty()) {
                continue;
            }
            if (CandidateFinder.find(state, item, config, constraints, new SearchStats(), budget, scorer, 1)
                    .isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
