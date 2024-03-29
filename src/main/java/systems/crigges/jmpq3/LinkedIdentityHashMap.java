package systems.crigges.jmpq3;

import java.util.*;

/**
 */
public class LinkedIdentityHashMap<K, V> extends IdentityHashMap<K, V> implements Iterable<K> {

    private static final long serialVersionUID = -3556728339380493443L;
    private final LinkedList<K> order = new LinkedList<K>();

    public LinkedIdentityHashMap() {
    }

    public LinkedIdentityHashMap(int expectedMaxSize) {
        super(expectedMaxSize);
    }

    @Override
    public V put(K key, V value) {
        final V oldValue = super.put(key, value);
        if (oldValue == null) {
            if (value != null) {
                order.add(key);
            }
        } else {
            if (value == null) {
                order.remove(key);
            }
        }
        return oldValue;
    }

    public Iterator<K> iterator() {
        return order.iterator();
    }

    public K first() {
        return order.getFirst();
    }

    public K last() {
        return order.getLast();
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof LinkedIdentityHashMap) {
            final LinkedIdentityHashMap map = (LinkedIdentityHashMap) other;
            if (order.size() != map.order.size()) {
                return false;
            }
            final Iterator iterator = map.order.iterator();
            for (K key : order) {
                if (key != iterator.next() || !get(key).equals(map.get(key))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return order.hashCode();
    }

    @Override
    public boolean remove(Object key, Object value) {
        order.remove(key);
        return super.remove(key, value);
    }

    @Override
    public V remove(Object key) {
        order.remove(key);
        return super.remove(key);
    }
}