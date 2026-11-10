package com.packvium.domain;

import com.packvium.unit.Length;
import java.util.LinkedHashMap;
import java.util.Map;

/** A corner position inside a container, in length ticks. */
public record Point(long x, long y, long z) {
    public static final Point ORIGIN = new Point(0, 0, 0);

    public Point {
        if (Math.min(x, Math.min(y, z)) < 0) {
            throw new IllegalArgumentException("Point coordinates cannot be negative");
        }
    }

    public Map<String, Object> toMap(String unit) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("x", new Length(x).toMap(unit));
        rendered.put("y", new Length(y).toMap(unit));
        rendered.put("z", new Length(z).toMap(unit));
        return rendered;
    }
}
