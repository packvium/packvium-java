package com.packvium.algorithm;

import com.packvium.config.PackingConfig;
import com.packvium.config.SolverProfile;
import com.packvium.domain.ItemInstance;
import com.packvium.extension.ItemOrderStrategy;
import com.packvium.support.SortKey;
import com.packvium.support.StableSorter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The distinct item orderings a portfolio tries, built-in strategies first, then seeded shuffles. */
final class ItemOrders {
    private ItemOrders() {
    }

    record Named(String name, List<ItemInstance> order) {
    }

    /** O(s * n log n) for s strategies, plus O(n) per shuffle attempt. */
    static List<Named> orders(List<ItemInstance> items, PackingConfig config, List<ItemOrderStrategy> customOrders) {
        long limit = config.profile() == SolverProfile.FAST ? 1 : config.multiStartOrders();
        List<Named> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Strategy strategy : strategies(config)) {
            if (offer(out, seen, strategy.name(), StableSorter.sortBy(items, strategy.key()), limit)) {
                return out;
            }
        }
        for (ItemOrderStrategy strategy : customOrders) {
            if (offer(out, seen, strategy.name(), strategy.order(items, config.seed()), limit)) {
                return out;
            }
        }
        DeterministicRandom random = new DeterministicRandom(config.seed());
        long attempts = 0;
        while (out.size() < limit && attempts < Math.max(32, limit * 16)) {
            attempts++;
            List<ItemInstance> shuffled = random.shuffled(items);
            String signature = signature(shuffled);
            if (seen.add(signature)) {
                out.add(new Named("seeded_" + out.size(), shuffled));
            }
        }
        return out;
    }

    /** Adds the order unless an identical one exists; true once the limit is reached. */
    private static boolean offer(List<Named> out, Set<String> seen, String name, List<ItemInstance> order, long limit) {
        if (seen.add(signature(order))) {
            out.add(new Named(name, order));
        }
        return out.size() >= limit;
    }

    private static String signature(List<ItemInstance> order) {
        return order.stream().map(ItemInstance::id).collect(Collectors.joining("|"));
    }

    private record Strategy(String name, Function<ItemInstance, SortKey> key) {
    }

    /** Every strategy is keyed behind ItemOrdering.lead, which carries priority, route stop and value. */
    private static List<Strategy> strategies(PackingConfig config) {
        List<Strategy> strategies = new ArrayList<>();
        if (config.profile() == SolverProfile.QUALITY) {
            strategies.add(new Strategy("small_edge", item -> key(item, config, item.dimensions().maxEdge(),
                    item.dimensions().volume(), item.id())));
            strategies.add(new Strategy("small_volume", item -> key(item, config, item.dimensions().volume(),
                    item.dimensions().maxEdge(), item.id())));
        }
        strategies.add(new Strategy("volume", item -> key(item, config, item.dimensions().volume().negate(),
                -item.dimensions().maxEdge(), -item.weight().ticks(), item.id())));
        strategies.add(new Strategy("base_area", item -> key(item, config, -item.dimensions().baseAreaTicks(),
                item.dimensions().volume().negate(), item.id())));
        strategies.add(new Strategy("weight", item -> key(item, config, -item.weight().ticks(),
                item.dimensions().volume().negate(), item.id())));
        strategies.add(new Strategy("constrained", item -> key(item, config,
                (long) item.item().allowedRotations().size(), -item.item().minimumSupportRatio(),
                item.dimensions().volume().negate(), item.id())));
        return strategies;
    }

    private static SortKey key(ItemInstance item, PackingConfig config, Object... tail) {
        List<Object> key = new ArrayList<>(ItemOrdering.lead(item, config));
        key.addAll(List.of(tail));
        return new SortKey(key);
    }
}
