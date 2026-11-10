package com.packvium.constraint;

import java.util.ArrayList;
import java.util.List;

public final class ConstraintSet {
    private ConstraintSet() {
    }

    public static List<PlacementConstraint> defaults(double support) {
        return defaults(support, List.of(), List.of());
    }

    public static List<PlacementConstraint> defaults(double support, List<PlacementConstraint> custom) {
        return defaults(support, custom, List.of());
    }

    public static List<PlacementConstraint> defaults(double support, List<PlacementConstraint> custom,
            List<String> accessDirections) {
        List<PlacementConstraint> constraints = new ArrayList<>(List.of(
                new FloorConstraint(), new ContainerEligibilityConstraint(), new CompatibilityConstraint(),
                new TagCountConstraint(), new SupportConstraint(support), new TopLoadConstraint(),
                new RouteOrderConstraint(), new StopAccessibilityConstraint(accessDirections),
                new AxleLoadConstraint()));
        constraints.addAll(custom);
        return List.copyOf(constraints);
    }
}
