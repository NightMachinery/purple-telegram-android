package org.telegram.messenger.purple;

import java.util.Objects;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

public final class PurpleSleepTimerViewerSelection {
    public enum Origin { REFRESH, USER_SELECTION, USER_OPEN }
    private Item selected;
    public boolean select(Item next, Origin origin) {
        if (next == null) return false;
        final boolean grantsPlay = origin == Origin.USER_OPEN
                || origin == Origin.USER_SELECTION && !Objects.equals(selected, next);
        selected = next;
        return grantsPlay;
    }
}
