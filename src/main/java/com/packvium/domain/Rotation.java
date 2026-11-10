package com.packvium.domain;

import java.util.List;

/** Which declared edge lies along the container's length, width and height axes. */
public enum Rotation {
    LWH, LHW, WLH, WHL, HLW, HWL;

    private static final List<Rotation> ALL = List.of(values());
    private static final List<Rotation> UPRIGHT = List.of(LWH, WLH);

    public static List<Rotation> all() {
        return ALL;
    }

    /** The rotations that keep the declared height vertical. */
    public static List<Rotation> upright() {
        return UPRIGHT;
    }
}
