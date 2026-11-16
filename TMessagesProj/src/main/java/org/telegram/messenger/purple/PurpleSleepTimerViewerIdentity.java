package org.telegram.messenger.purple;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

public final class PurpleSleepTimerViewerIdentity {
    private static final AtomicLong NEXT = new AtomicLong();
    private static final class Entry {
        final WeakReference<Object> owner;
        final Item item;
        Entry(Object owner, Item item) { this.owner = new WeakReference<>(owner); this.item = item; }
    }
    private static final ArrayList<Entry> entries = new ArrayList<>();
    public Item item(Source source, Object owner) {
        if (owner == null || source == Source.MESSAGE) return null;
        entries.removeIf(entry -> entry.owner.get() == null);
        for (Entry entry : entries) {
            if (entry.owner.get() == owner && entry.item.source == source) return entry.item;
        }
        final Item item = new Item(source, NEXT.incrementAndGet());
        entries.add(new Entry(owner, item));
        return item;
    }
}
