package com.packvium.domain;

import java.util.List;

/** An item instance left out of every container, with the reason and its proof. */
public record UnpackedItem(ItemInstance instance, String reason, List<String> details, ReasonProof proof) {
    public UnpackedItem {
        details = List.copyOf(details);
    }

    public UnpackedItem(ItemInstance instance, String reason, List<String> details) {
        this(instance, reason, details, ReasonProof.forReason(reason, details));
    }

    public UnpackedItem(ItemInstance instance, String reason) {
        this(instance, reason, List.of());
    }
}
