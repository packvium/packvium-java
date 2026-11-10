package com.packvium.domain;

import com.packvium.unit.Weight;

/** One physical copy of an item; {@code sequence} counts from 1 within its item. */
public record ItemInstance(Item item, long sequence) {
    public String id() {
        return item.id() + "#" + sequence;
    }

    public Dimensions dimensions() {
        return item.dimensions();
    }

    public Weight weight() {
        return item.weight();
    }
}
