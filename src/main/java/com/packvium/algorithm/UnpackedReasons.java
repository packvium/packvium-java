package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.ConstraintSet;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.ProvenRejection;
import com.packvium.constraint.Violation;
import com.packvium.constraint.SupportConstraint;
import com.packvium.constraint.AxleLoad;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.FlatBag;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.OrientedDimensions;
import com.packvium.domain.PackedContainer;
import com.packvium.domain.Placement;
import com.packvium.domain.Rotation;
import com.packvium.domain.UnpackedItem;
import com.packvium.extension.CandidateScorer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Why an item ended up unpacked, proven where pure geometry can prove it. */
final class UnpackedReasons {
    private final List<PlacementConstraint> customConstraints;
    private final CandidateScorer scorer;

    UnpackedReasons(List<PlacementConstraint> customConstraints, CandidateScorer scorer) {
        this.customConstraints = customConstraints;
        this.scorer = scorer;
    }

    /** Every left-out item's reason, against one final layout read once. */
    List<UnpackedItem> explainAll(List<ItemInstance> items, List<Container> containers, boolean reached,
            List<PackedContainer> packed, PackingConfig config) {
        Map<String, Long> opened = openedByType(packed);
        List<UnpackedItem> unpacked = new ArrayList<>(items.size());
        for (ItemInstance item : items) {
            unpacked.add(explain(item, containers, reached, packed, opened, config));
        }
        return unpacked;
    }

    private UnpackedItem explain(ItemInstance item, List<Container> containers, boolean reached,
            List<PackedContainer> packed, Map<String, Long> opened, PackingConfig config) {
        Reason reason = reason(item, containers, reached);
        String code = reason.code().equals("no_feasible_placement") && supportIsTheBlocker(item, packed, config)
                ? "insufficient_support"
                : reason.code();
        if (code.equals("no_feasible_placement")) {
            code = limitReason(item, containers, opened, config).orElse(code);
        }
        return new UnpackedItem(item, code, reason.details());
    }

    /** How many containers of each template a layout opened. O(P). */
    private static Map<String, Long> openedByType(List<PackedContainer> packed) {
        Map<String, Long> opened = new HashMap<>();
        packed.forEach(container -> opened.merge(container.container().id(), 1L, Long::sum));
        return opened;
    }

    /**
     * The container limit that left {@code item} out, for the no_feasible_placement fallback,
     * or empty when no limit explains it.
     *
     * <p>Only containers that admit the item on every structural count -- a fitting rotation, its
     * weight, its eligibility tags -- are asked. When an axle refuses the item alone in every one of
     * them, the axle is the reason. Otherwise, when every one that would take it alone is out of
     * stock, or max_containers is reached, the reason is the inventory: max_items, cumulative
     * max_payload, tag_limits, stop access and an axle's share of a full load can only refuse an
     * item a fresh container would take. O(C*R) for C templates and R rotations.
     */
    private static Optional<String> limitReason(ItemInstance item, List<Container> containers,
            Map<String, Long> opened, PackingConfig config) {
        List<Dimensions> envelopes = item.dimensions().uniqueRotations(item.item().allowedRotations()).stream()
                .map(oriented -> oriented.dimensions().expand(config.clearance())).toList();
        long weight = item.weight().ticks();
        List<String> eligibleTags = item.item().eligibleContainerTags();
        boolean compatible = false;
        List<Container> admitting = new ArrayList<>();
        for (Container container : containers) {
            List<Dimensions> fitting = envelopes.stream().filter(envelope -> fitsAlone(container, envelope)).toList();
            if (fitting.isEmpty()) {
                continue;
            }
            if (container.maxPayload().isPresent() && weight > container.maxPayload().get().ticks()) {
                continue;
            }
            if (!eligibleTags.isEmpty() && eligibleTags.stream().noneMatch(container.tags()::contains)) {
                continue;
            }
            compatible = true;
            if (axlesAdmitAlone(container, fitting, weight)) {
                admitting.add(container);
            }
        }
        if (!compatible) {
            return Optional.empty();
        }
        if (admitting.isEmpty()) {
            return Optional.of("axle_overloaded");
        }
        long total = opened.values().stream().mapToLong(Long::longValue).sum();
        if (config.maxContainers().isPresent() && total >= config.maxContainers().get()) {
            return Optional.of("container_inventory_exhausted");
        }
        boolean exhausted = admitting.stream().allMatch(container -> container.quantity().isPresent()
                && opened.getOrDefault(container.id(), 0L) >= container.quantity().get());
        return exhausted ? Optional.of("container_inventory_exhausted") : Optional.empty();
    }

    private static boolean axlesAdmitAlone(Container container, List<Dimensions> envelopes, long weight) {
        if (container.axles().isEmpty()) {
            return true;
        }
        for (Dimensions envelope : envelopes) {
            if (AxleLoad.admitsAlone(container.axles().get(), container.tareWeight().ticks(),
                    container.innerDimensions().length().ticks(), weight, envelope.length().ticks())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one box of these dimensions fits this container empty: the frame, and for a flat bag
     * the girth rule too, so a bag too thin for an item proves it unplaceable.
     */
    static boolean fitsAlone(Container container, Dimensions dimensions) {
        return container.isFlatBag() ? FlatBag.fitsAlone(container.innerDimensions(), dimensions)
                : dimensions.fitsInside(container.innerDimensions());
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
                if (fitsAlone(container, oriented.dimensions())) {
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
