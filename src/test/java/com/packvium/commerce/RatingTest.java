package com.packvium.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.packvium.commerce.rating.AccessorialCharge;
import com.packvium.commerce.rating.CarrierRegistry;
import com.packvium.commerce.rating.RateBreakdown;
import com.packvium.commerce.rating.RatingRequest;
import com.packvium.commerce.rating.TariffNotFoundException;
import com.packvium.commerce.rating.UnavailableServiceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Expected values are the v1.0.0 commerce golden documents produced by the reference engines. */
class RatingTest {
    @Test
    void ratesLargeShipmentsExactlyWithoutOverflow() {
        CarrierRegistry carriers = new CarrierRegistry();
        carriers.publish("bulk", "sea", 0, 7, Map.of("far", 999983L), 0, 999,
                Map.of("insurance", AccessorialCharge.permille("insurance", 997)));
        RateBreakdown breakdown = carriers.rateWithVersion(
                "bulk", "sea", 1, new RatingRequest("far", 1, 999999999, List.of("insurance")));
        assertEquals(142857143, breakdown.dimensionalWeightG());
        assertEquals(142857143, breakdown.billedWeightG());
        assertEquals(142854714429L, breakdown.baseChargeMinor());
        assertEquals(142711859715L, breakdown.fuelSurchargeMinor());
        assertEquals(List.of(Map.entry("insurance", 142426150286L)), breakdown.accessorialChargesMinor());
        assertEquals(427992724430L, breakdown.totalMinor());
    }

    @Test
    void appliesTheMinimumChargeFloor() {
        CarrierRegistry carriers = new CarrierRegistry();
        carriers.publish("a", "g", 0, 1000, Map.of("z", 100L), 500, 0, Map.of());
        RateBreakdown breakdown = carriers.rateWithVersion("a", "g", 1, new RatingRequest("z", 10, 0));
        assertTrue(breakdown.minimumChargeApplied());
        assertEquals(500, breakdown.baseChargeMinor());
        assertEquals(500, breakdown.totalMinor());
    }

    @Test
    void sameInstantCorrectionWinsOnTheHigherVersion() {
        CarrierRegistry carriers = new CarrierRegistry();
        carriers.publish("a", "g", 10, 1000, Map.of("z", 100L));
        carriers.publish("a", "g", 10, 1000, Map.of("z", 200L));
        assertEquals(2, carriers.effectiveTariff("a", "g", 10).version());
        TariffNotFoundException early = assertThrows(
                TariffNotFoundException.class, () -> carriers.effectiveTariff("a", "g", 9));
        assertEquals("a/g has no tariff version effective as of 9", early.getMessage());
    }

    @Test
    void namesUnavailableAccessorialsInCodePointOrder() {
        CarrierRegistry carriers = new CarrierRegistry();
        carriers.publish("a", "g", 0, 1000, Map.of("z", 100L));
        UnavailableServiceException missing = assertThrows(UnavailableServiceException.class,
                () -> carriers.rateWithVersion("a", "g", 1, new RatingRequest("z", 1, 1, List.of("😀", "￿", "b"))));
        assertEquals(List.of("b", "￿", "😀"), missing.accessorialIds());
        assertFalse(missing.zone().isPresent());
        assertEquals("tariff a/g v1 does not offer requested accessorial(s)", missing.getMessage());
    }

    @Test
    void refusesAnIntegerLikeAccessorialIdAsPhpDoes() {
        CarrierRegistry carriers = new CarrierRegistry();
        Map<String, AccessorialCharge> accessorials = new LinkedHashMap<>();
        accessorials.put("5", AccessorialCharge.flat("5", 1));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> carriers.publish("a", "g", 0, 1000, Map.of("z", 1L), 0, 0, accessorials));
        assertEquals("accessorials key must match its own accessorial_id: 5", refused.getMessage());
    }

    @Test
    void permilleAccessorialRoundsUp() {
        assertEquals(1, AccessorialCharge.permille("x", 1).chargeMinor(1));
        assertEquals(0, AccessorialCharge.permille("x", 1).chargeMinor(0));
    }
}
