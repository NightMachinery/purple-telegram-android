package org.telegram.ui;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.purple.PurpleFolderProjection;
import org.telegram.messenger.purple.PurpleFolderSelection;
import org.telegram.ui.Components.FilterTabsView;

import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

public final class PurpleFolderNavigation {
    private static final class Rendered {
        final PurpleFolderProjection.Frame frame;
        final int position;

        Rendered(PurpleFolderProjection.Frame frame, int position) {
            this.frame = frame;
            this.position = position;
        }
    }

    public static final class Attempt {
        final PurpleFolderProjection.Frame frame;
        final PurpleFolderSelection.Ticket<MessagesController.DialogFilter> ticket;
        final Object page;

        Attempt(PurpleFolderProjection.Frame frame,
                PurpleFolderSelection.Ticket<MessagesController.DialogFilter> ticket, Object page) {
            this.frame = frame;
            this.ticket = ticket;
            this.page = page;
        }

        public int position() {
            return ticket.position;
        }
    }

    public static final class Binding {
        final PurpleFolderProjection.Frame frame;
        final int position;
        final Object page;
        final long epoch;
        public final MessagesController.DialogFilter filter;

        Binding(PurpleFolderProjection.Frame frame, int position, Object page, long epoch) {
            this.frame = frame;
            this.position = position;
            this.page = page;
            this.epoch = epoch;
            filter = frame.filters.get(position);
        }
    }

    public static final class Rebuild {
        public final PurpleFolderProjection.Frame frame;
        public final int current;
        public final int incoming;
        final long epoch;

        Rebuild(PurpleFolderProjection.Frame frame, int current, int incoming, long epoch) {
            this.frame = frame;
            this.current = current;
            this.incoming = incoming;
            this.epoch = epoch;
        }
    }

    private final PurpleFolderSelection<MessagesController.DialogFilter> selection = new PurpleFolderSelection<>();
    private final WeakHashMap<FilterTabsView.Tab, Rendered> tabs = new WeakHashMap<>();
    private final WeakHashMap<Object, Binding> pages = new WeakHashMap<>();
    private PurpleFolderProjection.Frame rendered;
    private Attempt pending;
    private Attempt finalizing;
    private long epoch;
    private boolean closed;
    private boolean refreshPosted;
    private boolean refreshing;

    public void requestRefresh(Runnable refresh) {
        if (closed || refreshPosted || refreshing) return;
        refreshPosted = true;
        AndroidUtilities.runOnUIThread(() -> {
            refreshPosted = false;
            if (closed) return;
            refreshing = true;
            try {
                refresh.run();
            } finally {
                refreshing = false;
            }
        });
    }

    public FilterTabsView.PagePreparation prepare(int account, FilterTabsView.Tab tab, Object page,
            Runnable bind, BooleanSupplier visible, Runnable refresh, Runnable rollback) {
        long beforeRollback = epoch;
        rollback.run();
        if (!unchanged(beforeRollback)) return FilterTabsView.PagePreparation.REFUSED;
        Attempt attempt = begin(account, tab, page);
        if (attempt == null || needsRefresh(attempt)) {
            refresh.run();
            return FilterTabsView.PagePreparation.REFUSED;
        }
        bind.run();
        if (!visible.getAsBoolean() || !prepared(account, attempt)) {
            reject(attempt, rollback, refresh);
            return FilterTabsView.PagePreparation.REFUSED;
        }
        return FilterTabsView.PagePreparation.READY;
    }

    public void reject(Attempt attempt, Runnable rollback, Runnable refresh) {
        if (cancel(attempt)) {
            rollback.run();
            requestRefresh(refresh);
        }
    }

    public PurpleFolderProjection.Frame capture(int account) {
        return closed ? null : PurpleFolderProjection.capture(account);
    }

    public boolean changed(PurpleFolderProjection.Frame frame) {
        return !sameFrame(rendered, frame);
    }

    public void rendered(FilterTabsView strip, PurpleFolderProjection.Frame frame) {
        rendered = frame;
        for (int i = 0; i < frame.filters.size(); i++) {
            FilterTabsView.Tab tab = strip.getTab(i);
            if (tab != null) tabs.put(tab, new Rendered(frame, i));
        }
    }

    public Attempt begin(int account, FilterTabsView.Tab tab, Object page) {
        Rendered source = tabs.get(tab);
        PurpleFolderProjection.Frame current = capture(account);
        pending = null;
        finalizing = null;
        epoch++;
        selection.retire();
        if (source == null || current == null) return null;
        PurpleFolderSelection.Decision<MessagesController.DialogFilter> decision =
                selection.begin(source.frame.selection, source.position, current.selection);
        if (decision.ticket == null) return null;
        pending = new Attempt(current, decision.ticket, page);
        return pending;
    }

    public Attempt beginBound(int account, Object page) {
        Binding old = pages.get(page);
        if (old == null) return null;
        PurpleFolderProjection.Frame current = capture(account);
        epoch++;
        pending = null;
        finalizing = null;
        selection.retire();
        if (current == null) return null;
        PurpleFolderSelection.Decision<MessagesController.DialogFilter> decision =
                selection.begin(old.frame.selection, old.position, current.selection);
        if (decision.ticket == null) return null;
        pending = new Attempt(current, decision.ticket, page);
        return pending;
    }

    public long stamp() {
        return epoch;
    }

    public boolean unchanged(long stamp) {
        return !closed && epoch == stamp;
    }

    public boolean current(Rebuild rebuild) {
        return rebuild != null && unchanged(rebuild.epoch)
                && sameFrame(rebuild.frame, capture(rebuild.frame.account()));
    }

    public Attempt pending() {
        return pending;
    }

    public boolean owns(Attempt attempt) {
        return attempt != null && attempt == pending && !closed;
    }

    public boolean needsRefresh(Attempt attempt) {
        return owns(attempt) && !sameFrame(rendered, attempt.frame);
    }

    public boolean samePage(int account, FilterTabsView.Tab tab, Object page) {
        Rendered source = tabs.get(tab);
        Binding bound = pages.get(page);
        PurpleFolderProjection.Frame current = capture(account);
        if (source == null || bound == null || !sameFrame(rendered, current)) return false;
        int position = resolve(source.frame, source.position, current);
        return position >= 0 && bound.filter == current.filters.get(position)
                && resolve(bound.frame, bound.position, current) == position;
    }

    public Binding binding(int account, Object page, int position) {
        PurpleFolderProjection.Frame current = capture(account);
        if (current == null) return null;
        if (pending != null && pending.page == page) {
            if (!sameFrame(pending.frame, current)) return null;
            position = pending.position();
        } else {
            Binding old = pages.get(page);
            if (old != null) position = resolve(old.frame, old.position, current);
        }
        if (position < 0 || position >= current.filters.size()) return null;
        return new Binding(current, position, page, epoch);
    }

    public Binding rebuildBinding(Rebuild rebuild, Object page, boolean incoming) {
        int position = incoming ? rebuild.incoming : rebuild.current;
        return position < 0 ? null : new Binding(rebuild.frame, position, page, epoch);
    }

    public boolean current(Binding binding, Object page) {
        return binding != null && binding.page == page && current(binding);
    }

    public void restoreIndicator(FilterTabsView strip, Object page) {
        Binding binding = pages.get(page);
        if (binding == null || rendered == null) return;
        int position = resolve(binding.frame, binding.position, rendered);
        if (position >= 0) strip.selectTabWithId(position, 1f);
    }

    public boolean current(Binding binding) {
        if (binding == null || closed || binding.epoch != epoch || !binding.frame.current()) return false;
        return sameFrame(binding.frame, capture(binding.frame.account()));
    }

    public boolean bound(Binding binding) {
        if (!current(binding)) return false;
        pages.put(binding.page, binding);
        return true;
    }

    public int position(Binding binding) {
        return binding.position;
    }

    public boolean prepared(int account, Attempt attempt) {
        if (!owns(attempt)) return false;
        PurpleFolderProjection.Frame current = capture(account);
        Binding binding = pages.get(attempt.page);
        return current != null && binding != null && binding.filter == attempt.ticket.value
                && sameFrame(attempt.frame, current) && selection.prepared(attempt.ticket, current.selection);
    }

    public boolean finish(int account, Attempt attempt) {
        if (!prepared(account, attempt)) return false;
        PurpleFolderProjection.Frame current = capture(account);
        if (current == null || !selection.finish(attempt.ticket, current.selection)) return false;
        finalizing = attempt;
        pending = null;
        return true;
    }

    public void acknowledged(Attempt attempt) {
        if (finalizing == attempt) finalizing = null;
    }

    public boolean cancel(Attempt attempt) {
        if (!owns(attempt)) return false;
        selection.cancel(attempt.ticket);
        pending = null;
        epoch++;
        return true;
    }

    public Rebuild rebuild(PurpleFolderProjection.Frame frame, Object currentPage, Object incomingPage,
            boolean presetChanged) {
        if (closed || frame == null || !frame.current() || frame.filters.isEmpty()) return null;
        Attempt intent = pending != null ? pending : finalizing;
        Binding oldCurrent = pages.get(currentPage);
        Binding oldIncoming = pages.get(incomingPage);
        int current = intent == null ? -1 : resolve(intent.frame, intent.position(), frame);
        if (current < 0 && !presetChanged && oldCurrent != null) {
            current = resolve(oldCurrent.frame, oldCurrent.position, frame);
        }
        if (current < 0) current = firstUnlocked(frame);
        if (current < 0) return null;
        int incoming = oldIncoming == null ? -1 : resolve(oldIncoming.frame, oldIncoming.position, frame);
        if (incoming == current) incoming = -1;
        selection.retire();
        pending = null;
        finalizing = null;
        epoch++;
        return new Rebuild(frame, current, incoming, epoch);
    }

    public boolean alreadyBound(Object page, Binding binding) {
        Binding old = pages.get(page);
        return old != null && binding != null && old.filter == binding.filter;
    }

    public void forget(Object page) {
        pages.remove(page);
    }

    public void close() {
        closed = true;
        selection.retire();
        epoch++;
        pending = null;
        finalizing = null;
        rendered = null;
        tabs.clear();
        pages.clear();
    }

    private static int firstUnlocked(PurpleFolderProjection.Frame frame) {
        for (int i = 0; i < frame.filters.size(); i++) {
            if (!frame.filters.get(i).locked) return i;
        }
        return -1;
    }

    private static int resolve(PurpleFolderProjection.Frame source, int position,
            PurpleFolderProjection.Frame current) {
        PurpleFolderSelection<MessagesController.DialogFilter> probe = new PurpleFolderSelection<>();
        PurpleFolderSelection.Decision<MessagesController.DialogFilter> decision =
                probe.begin(source.selection, position, current.selection);
        return decision.ticket == null ? -1 : decision.ticket.position;
    }

    private static boolean sameFrame(PurpleFolderProjection.Frame a, PurpleFolderProjection.Frame b) {
        if (a == null || b == null || !a.current() || !b.current()
                || a.selection.revision != b.selection.revision
                || !a.selection.definition.equals(b.selection.definition)
                || !a.selection.account.key.equals(b.selection.account.key)
                || a.selection.account.lifetime != b.selection.account.lifetime
                || a.selection.account.role != b.selection.account.role
                || a.filters.size() != b.filters.size()) return false;
        for (int i = 0; i < a.filters.size(); i++) {
            if (a.filters.get(i) != b.filters.get(i)
                    || a.selection.entries.get(i).locked != b.selection.entries.get(i).locked) return false;
        }
        return true;
    }
}
