package com.packvium.constraint;

import com.packvium.domain.Container;
import com.packvium.domain.ItemInstance;
import java.util.List;
import java.util.Optional;

/**
 * A constraint that can rule an item out of <em>every</em> offered container without searching
 * for a placement first.
 *
 * <p>A rejected candidate only means <em>this</em> position failed. A constraint that implements
 * this can say that no position in any offered container could have worked, which lets the
 * unpacked reason be reported as {@code proven} and name the rule responsible.
 * Proving nothing is the correct answer for most rules: a segregation rule or a per-container
 * cap depends on what else was packed, a property of the search, not of the request.
 */
public interface ProvenRejection {
    Optional<Violation> provesUnplaceable(ItemInstance item, List<Container> containers);
}
