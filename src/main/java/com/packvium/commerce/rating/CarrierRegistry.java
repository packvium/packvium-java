package com.packvium.commerce.rating;

import com.packvium.support.Arithmetic;
import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-(carrier_id, service_id) append-only tariff history, and the rating itself.
 *
 * <p>This module computes a rate breakdown from first-party tariff data a caller publishes
 * into the registry -- it does not fetch, scrape or embed any real carrier's published
 * rates. Complexity: publish O(1); every resolution is one O(h) scan of that pair's
 * history; rating one request is O(a) in requested accessorials.
 */
public final class CarrierRegistry {
    private final Map<String, List<Tariff>> versions = new LinkedHashMap<>();

    public Tariff publish(String carrierId, String serviceId, long effectiveAt, long dimensionalWeightDivisor,
            Map<String, Long> costPerDimensionalKgMinor, long minimumChargeMinor, long fuelSurchargePermille,
            Map<String, AccessorialCharge> accessorials) {
        String key = key(carrierId, serviceId);
        List<Tariff> history = versions.getOrDefault(key, List.of());
        Tariff tariff = new Tariff(
                carrierId, serviceId, history.size() + 1L, effectiveAt, dimensionalWeightDivisor,
                costPerDimensionalKgMinor, minimumChargeMinor, fuelSurchargePermille, accessorials);
        // Registered only once the tariff validated, so a refused publish leaves no empty history.
        versions.computeIfAbsent(key, unused -> new ArrayList<>()).add(tariff);
        return tariff;
    }

    public Tariff publish(String carrierId, String serviceId, long effectiveAt, long dimensionalWeightDivisor,
            Map<String, Long> costPerDimensionalKgMinor) {
        return publish(carrierId, serviceId, effectiveAt, dimensionalWeightDivisor, costPerDimensionalKgMinor,
                0, 0, Map.of());
    }

    public List<Tariff> versions(String carrierId, String serviceId) {
        return List.copyOf(history(carrierId, serviceId));
    }

    public Tariff tariff(String carrierId, String serviceId, long version) {
        for (Tariff tariff : history(carrierId, serviceId)) {
            if (tariff.version() == version) {
                return tariff;
            }
        }
        throw new TariffNotFoundException(carrierId + "/" + serviceId + " has no version " + version);
    }

    /**
     * The version effective at asOf: the highest effective_at not after it, ties broken
     * by the higher (later-published) version number.
     */
    public Tariff effectiveTariff(String carrierId, String serviceId, long asOf) {
        Tariff winner = null;
        for (Tariff tariff : history(carrierId, serviceId)) {
            if (tariff.effectiveAt() > asOf) {
                continue;
            }
            if (winner == null
                    || tariff.effectiveAt() > winner.effectiveAt()
                    || (tariff.effectiveAt() == winner.effectiveAt() && tariff.version() > winner.version())) {
                winner = tariff;
            }
        }
        if (winner == null) {
            throw new TariffNotFoundException(
                    carrierId + "/" + serviceId + " has no tariff version effective as of " + asOf);
        }
        return winner;
    }

    public RateBreakdown rate(String carrierId, String serviceId, RatingRequest request, long asOf) {
        return rateTariff(effectiveTariff(carrierId, serviceId, asOf), request);
    }

    /**
     * The deterministic-offline-replay path: the same (carrier, service, version) and
     * the same request always reproduce the identical breakdown, whatever the history
     * has grown to since.
     */
    public RateBreakdown rateWithVersion(String carrierId, String serviceId, long version, RatingRequest request) {
        return rateTariff(tariff(carrierId, serviceId, version), request);
    }

    /** Rate a request against one already-resolved immutable tariff version. */
    public static RateBreakdown rateTariff(Tariff tariff, RatingRequest request) {
        requireOffered(tariff, request);

        // 1 mm^3 of volume divided by the tariff's divisor gives dimensional weight in
        // grams, rounded up -- never down, never through a float.
        long dimensionalWeightG = Arithmetic.mulDivCeil(request.volumeMm3(), 1, tariff.dimensionalWeightDivisor());
        long billedWeightG = Math.max(request.actualWeightG(), dimensionalWeightG);

        long rawBaseChargeMinor = Arithmetic.mulDivCeil(
                billedWeightG, tariff.costPerDimensionalKgMinor().get(request.zone()), 1000);
        boolean minimumApplied = rawBaseChargeMinor < tariff.minimumChargeMinor();
        long baseChargeMinor = minimumApplied ? tariff.minimumChargeMinor() : rawBaseChargeMinor;

        long fuelSurchargeMinor = Arithmetic.mulDivCeil(baseChargeMinor, tariff.fuelSurchargePermille(), 1000);

        List<Map.Entry<String, Long>> accessorialCharges = new ArrayList<>();
        long accessorialTotal = 0;
        for (String accessorialId : request.requestedAccessorials()) {
            long amount = tariff.accessorials().get(accessorialId).chargeMinor(baseChargeMinor);
            accessorialCharges.add(Map.entry(accessorialId, amount));
            accessorialTotal = Math.addExact(accessorialTotal, amount);
        }

        return new RateBreakdown(
                tariff.carrierId(), tariff.serviceId(), tariff.version(), request.zone(),
                request.actualWeightG(), dimensionalWeightG, billedWeightG, baseChargeMinor,
                minimumApplied, fuelSurchargeMinor, accessorialCharges,
                Math.addExact(Math.addExact(baseChargeMinor, fuelSurchargeMinor), accessorialTotal));
    }

    private static void requireOffered(Tariff tariff, RatingRequest request) {
        String label = "tariff " + tariff.carrierId() + "/" + tariff.serviceId() + " v" + tariff.version();
        if (!tariff.costPerDimensionalKgMinor().containsKey(request.zone())) {
            throw UnavailableServiceException.forZone(
                    label + " has no rate for zone '" + request.zone() + "'", request.zone());
        }
        List<String> unknown = new ArrayList<>();
        for (String accessorialId : request.requestedAccessorials()) {
            if (!tariff.accessorials().containsKey(accessorialId)) {
                unknown.add(accessorialId);
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(SortKey::compareCodePoints);
            throw UnavailableServiceException.forAccessorials(
                    label + " does not offer requested accessorial(s)", unknown);
        }
    }

    private List<Tariff> history(String carrierId, String serviceId) {
        List<Tariff> history = versions.get(key(carrierId, serviceId));
        if (history == null) {
            throw new TariffNotFoundException("no tariff registered for " + carrierId + "/" + serviceId);
        }
        return history;
    }

    private static String key(String carrierId, String serviceId) {
        return carrierId + "\0" + serviceId;
    }
}
