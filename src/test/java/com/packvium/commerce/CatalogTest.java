package com.packvium.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.packvium.commerce.catalog.AmbiguousReferenceException;
import com.packvium.commerce.catalog.CatalogRegistry;
import com.packvium.commerce.catalog.ItemMaster;
import com.packvium.commerce.catalog.NoEffectiveVersionException;
import com.packvium.commerce.catalog.Snapshot;
import com.packvium.commerce.catalog.Version;
import com.packvium.commerce.catalog.VersionNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Expected values are the v1.0.0 catalog results every engine returns. */
class CatalogTest {
    private static Snapshot items(String... ids) {
        List<ItemMaster> items = new java.util.ArrayList<>();
        for (String id : ids) {
            items.add(new ItemMaster(id, List.of(1L, 1L, 1L), 1));
        }
        return new Snapshot(items, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void rollbackIsANewVersionWithADefaultNote() {
        CatalogRegistry registry = new CatalogRegistry("c");
        registry.publish(items("a"), 0, 0);
        registry.publish(items("b"), 5, 5);
        Version rollback = registry.rollback(1, 9);
        assertEquals(3, rollback.number());
        assertEquals(9, rollback.effectiveAt());
        assertEquals(Optional.of(1L), rollback.rolledBackFrom());
        assertEquals("rollback to version 1", rollback.note());
        assertEquals(List.of("a"), rollback.snapshot().itemIds());
    }

    @Test
    void resolvesAsOfWithTheLaterVersionWinningTies() {
        CatalogRegistry registry = new CatalogRegistry("c");
        registry.publish(items("a"), 5, 0);
        registry.publish(items("b"), 5, 1);
        assertEquals(2, registry.resolve(0, Optional.empty(), Optional.of(5L)).number());
        NoEffectiveVersionException early = assertThrows(NoEffectiveVersionException.class,
                () -> registry.resolve(0, Optional.empty(), Optional.of(4L)));
        assertEquals("catalog 'c' has no version effective as of 4", early.getMessage());
    }

    @Test
    void anUnpinnedReferenceIsAmbiguousOnlyWithSeveralVersions() {
        CatalogRegistry registry = new CatalogRegistry("c");
        registry.publish(items("a"), 0, 0);
        assertEquals(1, registry.resolve(0, Optional.empty(), Optional.empty()).number());
        registry.publish(items("a"), 0, 0);
        AmbiguousReferenceException ambiguous = assertThrows(AmbiguousReferenceException.class,
                () -> registry.resolve(0, Optional.empty(), Optional.empty()));
        assertEquals("catalog 'c' has 2 versions; resolving requires an explicit version or as_of",
                ambiguous.getMessage());
        assertThrows(VersionNotFoundException.class, () -> registry.version(3));
    }

    @Test
    void aPinnedVersionIsReadByItsPosition() {
        CatalogRegistry registry = new CatalogRegistry("c");
        Version first = registry.publish(items("a"), 0, 0);
        Version second = registry.publish(items("b"), 5, 5);
        Version rollback = registry.rollback(1, 9);
        assertSame(first, registry.version(1));
        assertSame(second, registry.version(2));
        assertSame(rollback, registry.version(3));
        for (long missing : new long[] {0, -1, 4, Long.MAX_VALUE, Long.MIN_VALUE}) {
            VersionNotFoundException error = assertThrows(VersionNotFoundException.class, () -> registry.version(missing));
            assertEquals("catalog 'c' has no version " + missing, error.getMessage());
        }
    }

    @Test
    void sortsIdsByCodePoint() {
        assertEquals(List.of("B", "a", "￿", "😀"), items("😀", "a", "￿", "B").itemIds());
    }

    @Test
    void refusesDuplicateIds() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> items("a", "a"));
        assertEquals("duplicate item ids in catalog snapshot", duplicate.getMessage());
    }
}
