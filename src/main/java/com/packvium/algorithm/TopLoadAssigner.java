package com.packvium.algorithm;

import com.packvium.constraint.LoadCalculator;
import com.packvium.domain.Placement;
import com.packvium.unit.Weight;
import java.util.ArrayList;
import java.util.List;

public final class TopLoadAssigner {
    private TopLoadAssigner() {
    }

    /** Fills in each placement's borne load once the container is final. */
    public static List<Placement> assign(List<Placement> placements) {
        long[] loads = LoadCalculator.topLoads(LoadCalculator.units(placements));
        List<Placement> out = new ArrayList<>(placements.size());
        for (int index = 0; index < placements.size(); index++) {
            Placement p = placements.get(index);
            out.add(new Placement(p.instance(), p.position(), p.rotation(), p.dimensions(), p.envelopeOrigin(),
                    p.envelopeDimensions(), p.supportRatio(), new Weight(loads[index])));
        }
        return out;
    }
}
