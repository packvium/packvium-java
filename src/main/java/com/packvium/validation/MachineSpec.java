package com.packvium.validation;

import java.math.BigInteger;
import java.util.List;

/** A made-to-measure container's machine, in ticks, minor units and weight ticks. */
record MachineSpec(List<BigInteger> minimum, List<BigInteger> increment, BigInteger pricePerSquareMetreMinor,
        BigInteger tarePerSquareMetre, List<BigInteger> allowance) {
}
