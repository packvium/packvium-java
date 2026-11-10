package com.packvium.constraint;

public final class FloorConstraint implements PlacementConstraint {
    @Override
    public ConstraintResult evaluate(ConstraintContext context) {
        return context.item().item().mustBeOnFloor() && context.point().z() != 0
                ? ConstraintResult.reject("must_be_on_floor")
                : ConstraintResult.allow();
    }
}
