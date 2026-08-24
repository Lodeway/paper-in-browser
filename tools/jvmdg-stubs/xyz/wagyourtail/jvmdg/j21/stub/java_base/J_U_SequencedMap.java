package xyz.wagyourtail.jvmdg.j21.stub.java_base;

import xyz.wagyourtail.jvmdg.j21.impl.ReverseMap;
import xyz.wagyourtail.jvmdg.version.Adapter;
import xyz.wagyourtail.jvmdg.version.JEP;
import xyz.wagyourtail.jvmdg.version.Stub;

import java.util.*;

/**
 * Patched SequencedMap stub (Java 8 source level):
 * - firstEntry/lastEntry return null on an empty map (matching java.util.SequencedMap) instead of throwing
 * - NavigableMap implementations delegate to their native firstEntry/lastEntry/pollFirstEntry/pollLastEntry,
 *   which are atomic on concurrent maps (iterator-based emulation raced on ConcurrentSkipListMap)
 */
@JEP(431)
@Adapter(value = "java/util/SequencedMap", target = "java/util/Map")
public class J_U_SequencedMap {

    private J_U_SequencedMap() {
    }

    public static boolean jvmdg$instanceof(Object obj) {
        return obj instanceof LinkedHashMap<?, ?> ||
            obj instanceof SortedMap<?, ?> ||
            obj instanceof ReverseMap<?, ?, ?>;
    }

    public static Map<?, ?> jvmdg$checkcast(Object obj) {
        if (!jvmdg$instanceof(obj)) {
            throw new ClassCastException();
        }
        if (obj instanceof Map<?, ?>) {
            return (Map<?, ?>) obj;
        }
        throw new ClassCastException();
    }

    @Stub
    public static <K, V> Map<K, V> reversed(Map<K, V> self) {
        if (self instanceof NavigableMap<?, ?>) {
            return ((NavigableMap<K, V>) self).descendingMap();
        }
        if (self instanceof ReverseMap<?, ?, ?>) {
            return ((ReverseMap<K, V, ?>) self).original;
        }
        return new ReverseMap<>(self);
    }

    @Stub
    public static <K, V> Map.Entry<K, V> firstEntry(Map<K, V> self) {
        if (self instanceof NavigableMap<?, ?>) {
            return ((NavigableMap<K, V>) self).firstEntry();
        }
        Iterator<Map.Entry<K, V>> it = self.entrySet().iterator();
        return it.hasNext() ? it.next() : null;
    }

    @Stub
    public static <K, V> Map.Entry<K, V> lastEntry(Map<K, V> self) {
        if (self instanceof NavigableMap<?, ?>) {
            return ((NavigableMap<K, V>) self).lastEntry();
        }
        Iterator<Map.Entry<K, V>> it = reversed(self).entrySet().iterator();
        return it.hasNext() ? it.next() : null;
    }

    @Stub
    public static <K, V> Map.Entry<K, V> pollFirstEntry(Map<K, V> self) {
        if (self instanceof NavigableMap<?, ?>) {
            return ((NavigableMap<K, V>) self).pollFirstEntry();
        }
        Iterator<Map.Entry<K, V>> it = self.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        Map.Entry<K, V> entry = it.next();
        it.remove();
        return entry;
    }

    @Stub
    public static <K, V> Map.Entry<K, V> pollLastEntry(Map<K, V> self) {
        if (self instanceof NavigableMap<?, ?>) {
            return ((NavigableMap<K, V>) self).pollLastEntry();
        }
        return pollFirstEntry(reversed(self));
    }

    @Stub
    public static <K, V> V putFirst(Map<K, V> self, K key, V value) {
        if (self instanceof ReverseMap<?, ?, ?> && ((ReverseMap<K, V, ?>) self).original instanceof LinkedHashMap<?, ?>) {
            return ((ReverseMap<K, V, ?>) self).original.put(key, value);
        }
        throw new UnsupportedOperationException();
    }

    @Stub
    public static <K, V> V putLast(Map<K, V> self, K key, V value) {
        if (self instanceof LinkedHashMap<?, ?>) {
            return self.put(key, value);
        }
        throw new UnsupportedOperationException();
    }

    @Stub
    public static <K> Set<K> sequencedKeySet(Map<K, ?> self) {
        return self.keySet();
    }

    @Stub
    public static <V> Collection<V> sequencedValues(Map<?, V> self) {
        return self.values();
    }

    @Stub
    public static <K, V> Set<Map.Entry<K, V>> sequencedEntrySet(Map<K, V> self) {
        return self.entrySet();
    }

}
