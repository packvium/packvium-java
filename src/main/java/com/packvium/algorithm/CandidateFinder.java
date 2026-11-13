package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.constraint.AxleLoad;
import com.packvium.constraint.ConstraintContext;
import com.packvium.constraint.LoadCalculator;
import com.packvium.constraint.PlacementConstraint;
import com.packvium.constraint.SupportConstraint;
import com.packvium.constraint.internal.ConstraintActivity;
import com.packvium.domain.Container;
import com.packvium.domain.Dimensions;
import com.packvium.domain.HullShape;
import com.packvium.domain.ItemInstance;
import com.packvium.domain.Nesting;
import com.packvium.domain.OrientedDimensions;
import com.packvium.domain.Placement;
import com.packvium.domain.Point;
import com.packvium.domain.Rotation;
import com.packvium.domain.ShapeType;
import com.packvium.extension.CandidateScorer;
import com.packvium.support.StableSorter;
import com.packvium.unit.Weight;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Feasible placements of one item in one container state, best first.
 *
 * <p>O(p * r * (q + k)) per call for p scanned points (capped at maxCandidatePoints), r distinct
 * rotations, q solids returned by the spatial index per query and k constraint checks.
 */
public final class CandidateFinder {
    private CandidateFinder() {
    }

    /** Every feasible placement, best first, from the state's own points. */
    public static List<Candidate> find(ContainerState state, ItemInstance item, PackingConfig config,
            List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline, CandidateScorer scorer) {
        return find(state, item, config, constraints, stats, deadline, scorer, Optional.empty(), Optional.empty());
    }

    /** At most {@code max} feasible placements, best first, from the state's own points. */
    public static List<Candidate> find(ContainerState state, ItemInstance item, PackingConfig config,
            List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline, CandidateScorer scorer,
            int max) {
        return find(state, item, config, constraints, stats, deadline, scorer, Optional.of(max), Optional.empty());
    }

    /** Feasible placements, best first; {@code points} restricts the scan to those origins. */
    public static List<Candidate> find(ContainerState state, ItemInstance item, PackingConfig config,
            List<PlacementConstraint> constraints, SearchStats stats, Deadline deadline, CandidateScorer scorer,
            Optional<Integer> max, Optional<List<Point>> points) {
        Container container = state.container;
        if (container.maxItems().isPresent() && state.placements.size() >= container.maxItems().get()) {
            return List.of();
        }
        if (container.maxPayload().isPresent()
                && state.payloadTicks + item.weight().ticks() > container.maxPayload().get().ticks()) {
            return List.of();
        }
        boolean compressionSensitive = state.compressionSensitive
                || item.item().shapeType() == ShapeType.COMPRESSIBLE;
        boolean reserveNeedsCandidate = item.item().nestingHeight().isPresent()
                || item.item().shapeType() == ShapeType.CONVEX_HULL || compressionSensitive;
        if (container.voidFillReserveRatio() > 0 && !reserveNeedsCandidate
                && state.usedVolume.add(item.dimensions().volume()).compareTo(state.usableVolume()) > 0) {
            return List.of();
        }
        List<Form> forms = forms(item, config);
        Scan scan = new Scan(state, item, config, constraints, stats, deadline, scorer, max, compressionSensitive,
                reserveNeedsCandidate);
        for (Point point : scanOrder(state, item, config, forms, points)) {
            scan.visit(point, forms);
        }
        return scan.result();
    }

    /**
     * Envelope and extents depend only on the rotation, so they are built once per call. A hull is
     * not the same solid under two rotations that give the same box, so hulls keep every allowed
     * rotation; cuboids keep the box deduplication.
     */
    private static List<Form> forms(ItemInstance item, PackingConfig config) {
        long clearance = config.clearance().ticks();
        boolean isHull = item.item().shapeType() == ShapeType.CONVEX_HULL;
        boolean exactHull = Placement.hullCollisionIsExact(item.item(), clearance == 0);
        List<OrientedDimensions> rotations = isHull
                ? item.item().allowedRotations().stream()
                        .map(rotation -> new OrientedDimensions(rotation, item.dimensions().rotated(rotation))).toList()
                : item.dimensions().uniqueRotations(item.item().allowedRotations());
        List<Form> forms = new ArrayList<>();
        for (OrientedDimensions oriented : rotations) {
            Dimensions physical = oriented.dimensions();
            Dimensions envelope = clearance != 0 ? physical.expand(config.clearance()) : physical;
            Optional<HullShape> shape = exactHull
                    ? Optional.of(HullShape.shapeFor(item.item().hullVertices().orElseThrow(), oriented.rotation()))
                    : Optional.empty();
            forms.add(new Form(oriented.rotation(), physical, envelope, shape));
        }
        return forms;
    }

    private static List<Point> scanOrder(ContainerState state, ItemInstance item, PackingConfig config,
            List<Form> forms, Optional<List<Point>> restricted) {
        if (restricted.isPresent()) {
            List<Point> scan = new ArrayList<>(restricted.get());
            scan.sort(ContainerState::compareZyx);
            return scan;
        }
        int cap = (int) Math.min(config.maxCandidatePoints(), Integer.MAX_VALUE);
        List<Point> scan;
        if (item.item().nestingHeight().isEmpty()) {
            scan = new ArrayList<>(state.orderedPoints.subList(0, Math.min(cap, state.orderedPoints.size())));
        } else {
            LinkedHashSet<Point> merged = new LinkedHashSet<>(state.orderedPoints);
            merged.addAll(nestingPoints(state, item));
            scan = new ArrayList<>(merged);
            scan.sort(ContainerState::compareZyx);
            scan = new ArrayList<>(scan.subList(0, Math.min(cap, scan.size())));
        }
        if (state.container.axles().isPresent()) {
            scan.addAll(axleBalancedPoints(state, item, forms));
        }
        return scan;
    }

    private static List<Point> nestingPoints(ContainerState state, ItemInstance item) {
        long depth = item.item().nestingHeight().orElseThrow().ticks();
        LinkedHashSet<Point> points = new LinkedHashSet<>();
        for (Placement placement : state.placements) {
            if (!placement.instance().item().id().equals(item.item().id())) {
                continue;
            }
            long z = placement.envelopeBox().z2() - depth;
            if (z >= 0) {
                points.add(new Point(placement.envelopeOrigin().x(), placement.envelopeOrigin().y(), z));
            }
        }
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(ContainerState::compareZyx);
        return sorted;
    }

    /**
     * Floor-level candidates that seat the item on an axle's own limit. Every other point is
     * flush against a wall or a box, but with axle limits the only feasible spot can be open
     * floor, chosen to keep the item's moment off one axle (see AxleLoad.axleBalancedOrigins).
     */
    private static List<Point> axleBalancedPoints(ContainerState state, ItemInstance item, List<Form> forms) {
        Container container = state.container;
        var otherUnits = LoadCalculator.units(state.placements);
        long tareTicks = container.tareWeight().ticks();
        long tareDoubledX = container.innerDimensions().length().ticks();
        long limitX = container.innerDimensions().length().ticks();
        LinkedHashSet<Long> floorYs = new LinkedHashSet<>();
        for (Point point : state.points) {
            if (point.z() == 0) {
                floorYs.add(point.y());
            }
        }
        if (floorYs.isEmpty()) {
            floorYs.add(0L);
        }
        List<Point> points = new ArrayList<>();
        for (Form form : forms) {
            long dx = form.dx();
            for (long x1 : AxleLoad.axleBalancedOrigins(container.axles().orElseThrow(), otherUnits, tareTicks,
                    tareDoubledX, item.weight().ticks(), dx)) {
                if (x1 < 0 || x1 > limitX - dx) {
                    continue;
                }
                for (long y : floorYs) {
                    points.add(new Point(x1, y, 0));
                }
            }
        }
        return points;
    }

    private record Form(Rotation rotation, Dimensions physical, Dimensions envelope, Optional<HullShape> shape) {
        long dx() {
            return envelope.length().ticks();
        }

        long dy() {
            return envelope.width().ticks();
        }

        long dz() {
            return envelope.height().ticks();
        }
    }

    /** The per-call scan state: what has been found so far and the inputs every point shares. */
    private static final class Scan {
        private final ContainerState state;
        private final ItemInstance item;
        private final PackingConfig config;
        /** The chain with every rule that cannot fire for this item in this container removed. */
        private final PlacementConstraint[] active;
        /** Parallel to {@link #active}: whether reaching the rule counts as a support check. */
        private final boolean[] countsSupport;
        private final SearchStats stats;
        private final Deadline deadline;
        private final CandidateScorer scorer;
        private final Optional<Integer> max;
        private final boolean compressionSensitive;
        private final boolean nesting;
        private final boolean reserveCheck;
        private final BigInteger usable;
        private final boolean stackSensitive;
        private final boolean routeSensitive;
        private final int placementOffset;
        private final List<Candidate> found = new ArrayList<>();
        private Candidate best;

        Scan(ContainerState state, ItemInstance item, PackingConfig config, List<PlacementConstraint> constraints,
                SearchStats stats, Deadline deadline, CandidateScorer scorer, Optional<Integer> max,
                boolean compressionSensitive, boolean reserveNeedsCandidate) {
            this.state = state;
            this.item = item;
            this.config = config;
            this.stats = stats;
            this.deadline = deadline;
            this.scorer = scorer;
            this.max = max;
            this.compressionSensitive = compressionSensitive;
            this.nesting = item.item().nestingHeight().isPresent();
            this.reserveCheck = state.container.voidFillReserveRatio() > 0 && reserveNeedsCandidate;
            this.usable = reserveCheck ? state.usableVolume() : BigInteger.ZERO;
            this.stackSensitive = state.stackSensitive || item.item().isStackSensitive()
                    || state.container.maxStackDensity().isPresent();
            this.routeSensitive = state.routeSensitive || item.item().stopIndex().isPresent();
            this.placementOffset = state.bounds.size() - state.placements.size();
            // Everything that decides whether a rule can fire is fixed for this call, so the chain
            // is pruned once here rather than answered "allow" once per position. The support rule
            // stays in regardless: supportChecks counts every time the chain reaches it.
            List<PlacementConstraint> kept = new ArrayList<>(constraints.size());
            for (PlacementConstraint constraint : constraints) {
                if (!ConstraintActivity.isInert(constraint, state.container, item, stackSensitive, routeSensitive)) {
                    kept.add(constraint);
                }
            }
            this.active = kept.toArray(PlacementConstraint[]::new);
            this.countsSupport = new boolean[active.length];
            for (int index = 0; index < active.length; index++) {
                countsSupport[index] = active[index] instanceof SupportConstraint;
            }
        }

        void visit(Point point, List<Form> forms) {
            deadline.check();
            stats.candidatePointsConsidered++;
            var inner = state.container.innerDimensions();
            for (Form form : forms) {
                stats.placementsAttempted++;
                long x2 = point.x() + form.dx();
                long y2 = point.y() + form.dy();
                long z2 = point.z() + form.dz();
                if (x2 > inner.length().ticks() || y2 > inner.width().ticks() || z2 > inner.height().ticks()) {
                    continue;
                }
                long clearance = config.clearance().ticks();
                Point position = new Point(point.x() + clearance, point.y() + clearance, point.z() + clearance);
                Optional<Placement> tentative = nesting
                        ? Optional.of(placementAt(position, point, form))
                        : Optional.empty();
                if (blocked(point, x2, y2, z2, form, tentative) || !allowed(point, form)) {
                    continue;
                }
                Candidate candidate = new Candidate(point, position, form.rotation(), form.physical(),
                        form.envelope(), scorer.score(state, point, form.envelope()));
                if (reserveCheck && exceedsReserve(tentative.orElseGet(() -> placementAt(position, point, form)))) {
                    continue;
                }
                stats.candidatesEvaluated++;
                keep(candidate);
            }
        }

        private Placement placementAt(Point position, Point point, Form form) {
            return new Placement(item, position, form.rotation(), form.physical(), point, form.envelope(), 1.0,
                    Weight.ZERO);
        }

        private boolean blocked(Point point, long x2, long y2, long z2, Form form, Optional<Placement> tentative) {
            long x1 = point.x();
            long y1 = point.y();
            long z1 = point.z();
            for (int candidateIndex : state.index.query(x1, y1, z1, x2, y2, z2)) {
                long[] b = state.bounds.get(candidateIndex);
                stats.collisionChecks++;
                if (!(x1 < b[3] && b[0] < x2 && y1 < b[4] && b[1] < y2 && z1 < b[5] && b[2] < z2)) {
                    continue;
                }
                int placementIndex = candidateIndex - placementOffset;
                if (tentative.isPresent() && placementIndex >= 0
                        && Nesting.isValidNesting(state.placements.get(placementIndex), tentative.get())) {
                    continue;
                }
                Optional<HullShape> blocker = state.hullShapes.get(candidateIndex);
                // The box test is the broad phase; only a hull on either side may overrule it, so
                // a request of ordinary boxes never reaches the exact test.
                if ((form.shape().isPresent() || blocker.isPresent()) && !HullShape.collide(
                        form.shape().orElseGet(() -> HullShape.box(form.dx(), form.dy(), form.dz())),
                        new long[] {x1, y1, z1},
                        blocker.orElseGet(() -> HullShape.box(b[3] - b[0], b[4] - b[1], b[5] - b[2])),
                        new long[] {b[0], b[1], b[2]})) {
                    continue;
                }
                return true;
            }
            return false;
        }

        private boolean allowed(Point point, Form form) {
            ConstraintContext context = new ConstraintContext(state.container, state.placements, item, point,
                    form.rotation(), form.physical(), form.envelope(), stackSensitive, routeSensitive);
            for (int index = 0; index < active.length; index++) {
                if (countsSupport[index]) {
                    stats.supportChecks++;
                }
                if (!active[index].evaluate(context).allowed()) {
                    return false;
                }
            }
            return true;
        }

        private boolean exceedsReserve(Placement reservePlacement) {
            BigInteger projected;
            if (compressionSensitive) {
                // A zero-load candidate is at its largest, and adding load only shrinks existing
                // compressible supports. If that upper bound fits, the exact non-local refresh
                // cannot reject it; only candidates near the reserve boundary pay for it.
                BigInteger upperBound = state.usedVolume.add(Nesting.occupiedVolume(reservePlacement));
                if (upperBound.compareTo(usable) <= 0) {
                    projected = upperBound;
                } else {
                    List<Placement> withCandidate = new ArrayList<>(state.placements);
                    withCandidate.add(reservePlacement);
                    projected = Nesting.usedVolume(TopLoadAssigner.assign(withCandidate));
                }
            } else {
                projected = state.usedVolume.add(Nesting.usedVolumeDelta(state.placements, reservePlacement));
            }
            return projected.compareTo(usable) > 0;
        }

        private void keep(Candidate candidate) {
            if (max.isPresent() && max.get() == 1) {
                if (best == null || candidate.score().compareTo(best.score()) < 0) {
                    best = candidate;
                }
                return;
            }
            found.add(candidate);
        }

        List<Candidate> result() {
            if (max.isPresent() && max.get() == 1) {
                return best == null ? List.of() : List.of(best);
            }
            List<Candidate> sorted = StableSorter.sortBy(found, Candidate::score);
            return max.map(limit -> sorted.subList(0, Math.min(limit, sorted.size()))).map(List::copyOf)
                    .orElse(sorted);
        }
    }
}
