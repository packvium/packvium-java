package com.packvium.validation;

/**
 * One placement as the container checks see it: its item id as the result spells it, its
 * envelope, the placement, and its item type, or null when the request declares none.
 */
record Entry(Object id, Cuboid envelope, Object placement, ItemSpec spec) {
}
