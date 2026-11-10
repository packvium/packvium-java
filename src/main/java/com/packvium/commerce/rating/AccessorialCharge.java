package com.packvium.commerce.rating;

import com.packvium.support.Arithmetic;
import java.util.Optional;

/**
 * One named accessorial (residential delivery, liftgate, signature-required), either a
 * flat charge or a permille-of-base charge -- never both.
 */
public record AccessorialCharge(String accessorialId, Optional<Long> flatChargeMinor, Optional<Long> permilleOfBase) {
    public AccessorialCharge {
        if (accessorialId.isEmpty()) {
            throw new IllegalArgumentException("accessorial_id is required");
        }
        if (flatChargeMinor.isEmpty() == permilleOfBase.isEmpty()) {
            throw new IllegalArgumentException(
                    "an accessorial must set exactly one of flat_charge_minor or permille_of_base");
        }
        if (flatChargeMinor.isPresent() && flatChargeMinor.get() < 0) {
            throw new IllegalArgumentException("flat_charge_minor cannot be negative");
        }
        if (permilleOfBase.isPresent() && permilleOfBase.get() < 0) {
            throw new IllegalArgumentException("permille_of_base cannot be negative");
        }
    }

    public static AccessorialCharge flat(String accessorialId, long flatChargeMinor) {
        return new AccessorialCharge(accessorialId, Optional.of(flatChargeMinor), Optional.empty());
    }

    public static AccessorialCharge permille(String accessorialId, long permilleOfBase) {
        return new AccessorialCharge(accessorialId, Optional.empty(), Optional.of(permilleOfBase));
    }

    public long chargeMinor(long baseChargeMinor) {
        if (flatChargeMinor.isPresent()) {
            return flatChargeMinor.get();
        }
        return Arithmetic.mulDivCeil(baseChargeMinor, permilleOfBase.orElseThrow(), 1000);
    }
}
