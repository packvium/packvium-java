package com.packvium.simulation;

import com.packvium.support.SortKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The exact, recorded configuration one scenario ran against.
 *
 * <p>Every field is an explicit version number, or empty when that registry was not consulted --
 * never "current". A stored {@link ScenarioResult} therefore replays byte for byte however far
 * the registries' histories have grown since, the same guarantee a pinned catalog or tariff
 * version gives one lookup. The numbers are the ones {@code CatalogRegistry},
 * {@code CarrierRegistry} and {@code PolicyRegistry} hand out.
 *
 * @param catalogVersion the catalog version the scenario resolved cartons against
 * @param tariffVersion the tariff version it priced against
 * @param policyVersion a whole-policy version, where the caller versions policy as one unit
 * @param policyVersions individual policy rules pinned by version; stored sorted by rule id
 * @param solverVersion the engine release that packed it, such as {@code 1.2.0}
 */
public record ScenarioVersionPin(
        Optional<Long> catalogVersion,
        Optional<Long> tariffVersion,
        Optional<Long> policyVersion,
        List<PolicyVersionPin> policyVersions,
        Optional<String> solverVersion) {
    private static final Comparator<PolicyVersionPin> BY_RULE = Comparator
            .comparing(PolicyVersionPin::ruleId, SortKey::compareCodePoints)
            .thenComparingLong(PolicyVersionPin::version);

    /** Refuses an empty pin, a non-positive version, a rule pinned twice and an empty solver version. */
    public ScenarioVersionPin {
        if (catalogVersion.isEmpty() && tariffVersion.isEmpty() && policyVersion.isEmpty()
                && solverVersion.isEmpty() && policyVersions.isEmpty()) {
            throw new IllegalArgumentException("a version pin must set at least one of its fields");
        }
        for (Optional<Long> numeric : List.of(catalogVersion, tariffVersion, policyVersion)) {
            if (numeric.isPresent() && numeric.get() <= 0) {
                throw new IllegalArgumentException("version numbers must be positive");
            }
        }
        Set<String> ruleIds = new HashSet<>();
        for (PolicyVersionPin pin : policyVersions) {
            if (pin.ruleId() == null || pin.ruleId().isEmpty() || pin.version() <= 0) {
                throw new IllegalArgumentException("policy version pins require a rule id and positive version");
            }
            ruleIds.add(pin.ruleId());
        }
        if (ruleIds.size() != policyVersions.size()) {
            throw new IllegalArgumentException("a version pin cannot name the same policy rule twice");
        }
        List<PolicyVersionPin> sorted = new ArrayList<>(policyVersions);
        sorted.sort(BY_RULE);
        policyVersions = List.copyOf(sorted);
        if (solverVersion.isPresent() && solverVersion.get().isEmpty()) {
            throw new IllegalArgumentException("solver_version must be non-empty when supplied");
        }
    }

    /** A pin that names only a catalog version, the common case of a carton-set comparison. */
    public static ScenarioVersionPin ofCatalogVersion(long catalogVersion) {
        return new ScenarioVersionPin(Optional.of(catalogVersion), Optional.empty(), Optional.empty(), List.of(),
                Optional.empty());
    }
}
