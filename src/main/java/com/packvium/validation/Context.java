package com.packvium.validation;

import java.math.BigInteger;
import java.util.List;

/** The parts of the request every per-item check reads; {@code rules} are the participating {@code require_container_tag} rules. */
record Context(Ordered<ItemSpec> items, Ordered<ContainerSpec> containers, BigInteger clearance, List<Object> rules) {
}
