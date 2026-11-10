package com.packvium.constraint;

import com.packvium.constraint.internal.DirectSupportView;
import com.packvium.constraint.internal.LoadSupportGraph;
import com.packvium.domain.AxisAlignedBox;
import com.packvium.domain.ItemInstance;
import com.packvium.support.Formats;
import java.util.List;
import java.util.Optional;

/**
 * Support ratios arrive as floats from the public API but must never decide feasibility in
 * floating point. The ratio is converted once to a scaled integer and the comparison below is
 * exact. O(n) per candidate for the support view plus O(s log s) for the s supporters' hull.
 */
public final class SupportConstraint implements PlacementConstraint {
    public static final long SCALE = 1_000_000;

    private final double globalMinimum;

    public SupportConstraint() {
        this(0.0);
    }

    public SupportConstraint(double globalMinimum) {
        this.globalMinimum = globalMinimum;
    }

    /**
     * Whether this constraint can reject some non-floor placement of {@code item}. Solver
     * diagnostics ask every registered SupportConstraint, including caller-supplied ones with
     * their own global minimum, so an inactive default can be skipped without silently
     * ignoring an active custom one.
     */
    public boolean canReject(ItemInstance item) {
        Optional<String> rule = item.item().groundContactRule();
        return (rule.isPresent() && !rule.get().equals("free"))
                || Math.max(globalMinimum, item.item().minimumSupportRatio()) > 0;
    }

    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        if (context.point().z() == 0) {
            return ConstraintResult.allow();
        }
        if (!canReject(context.item())) {
            return ConstraintResult.allow();
        }
        AxisAlignedBox box = context.envelopeBox();
        DirectSupportView support = LoadSupportGraph.candidateView(context.placements(), context.item(), box);
        List<AxisAlignedBox> surfaces = support.surfaces();
        List<AxisAlignedBox> supporters = support.supporterBoxes();
        Optional<String> rule = context.item().item().groundContactRule();
        if (rule.isPresent() && !rule.get().equals("free")) {
            int count = supporters.size();
            if (rule.get().equals("covered") && !touchesCorners(box, surfaces)) {
                return ConstraintResult.reject("ground_contact_violation",
                        "covered: " + count + " supporter(s), not all four corners touched");
            }
            if (rule.get().equals("single") && count != 1) {
                return ConstraintResult.reject("ground_contact_violation", "single: rests on " + count + " item(s)");
            }
            if (rule.get().equals("multiple") && count < 2) {
                return ConstraintResult.reject("ground_contact_violation",
                        "multiple: rests on " + count + " item(s)");
            }
        }
        double required = Math.max(globalMinimum, context.item().item().minimumSupportRatio());
        if (required <= 0) {
            return ConstraintResult.allow();
        }
        long area = support.supportingArea();
        long baseArea = context.envelopeDimensions().baseAreaTicks();
        if (area < requiredArea(baseArea, scaled(required))) {
            return ConstraintResult.reject("insufficient_support",
                    area + "/" + baseArea + " < " + Formats.fixed6(required));
        }
        // Area alone is not stability: a candidate can clear the ratio while
        // overhanging its own centre of gravity, which the ratio check above cannot see.
        // Checked only once a minimum ratio is already being enforced, so a caller who never
        // asked for support checking sees no new rejection code and no behaviour change.
        List<Point2D> hull = SupportPolygon.convexHull(SupportPolygon.contactHullPoints(box, supporters));
        if (!SupportPolygon.pointInHull(SupportPolygon.doubledCentroid(box), hull)) {
            return ConstraintResult.reject("centre_of_gravity_unsupported",
                    area + "/" + baseArea + " met but centroid outside the " + hull.size() + "-point support hull");
        }
        return ConstraintResult.allow();
    }

    /**
     * Whether every one of the candidate's four base corners rests on some surface. A ratio
     * cannot express this: 90% of the footprint concentrated in the middle passes any ratio
     * below that but leaves every corner hanging. Public because the loading-prefix replay
     * reuses this exact calculation. O(4 * s).
     */
    public static boolean touchesCorners(AxisAlignedBox box, List<AxisAlignedBox> surfaces) {
        long[][] corners = {
            {box.origin().x(), box.origin().y()}, {box.x2(), box.origin().y()},
            {box.origin().x(), box.y2()}, {box.x2(), box.y2()},
        };
        for (long[] corner : corners) {
            boolean touched = false;
            for (AxisAlignedBox surface : surfaces) {
                if (surface.origin().x() <= corner[0] && corner[0] <= surface.x2()
                        && surface.origin().y() <= corner[1] && corner[1] <= surface.y2()) {
                    touched = true;
                    break;
                }
            }
            if (!touched) {
                return false;
            }
        }
        return true;
    }

    public static long scaled(double ratio) {
        return (long) (ratio * SCALE + 0.5);
    }

    /** Exact floor(baseArea * scaled / SCALE) without an overflowing product. */
    public static long requiredArea(long baseArea, long scaled) {
        return Math.addExact(Math.multiplyExact(baseArea / SCALE, scaled),
                Math.multiplyExact(baseArea % SCALE, scaled) / SCALE);
    }
}
