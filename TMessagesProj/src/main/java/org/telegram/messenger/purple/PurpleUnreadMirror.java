package org.telegram.messenger.purple;

public final class PurpleUnreadMirror {
    private final int currentAccount;
    private final int[][][] counters;
    private final int[][][] snapshot;
    private final int[][][] shown;
    private final int[][] mentions;
    private final int[][] mentionSnapshot;
    private int uncounted;
    private int unshown;

    private PurpleUnreadMirror(int currentAccount, boolean fullPass,
            int[][] contacts, int[][] nonContacts, int[][] bots, int[][] channels,
            int[][] groups, int[][] communities, int[] mentionChannels, int[] mentionGroups) {
        this.currentAccount = currentAccount;
        counters = new int[][][]{contacts, nonContacts, bots, channels, groups, communities};
        snapshot = new int[counters.length][][];
        shown = fullPass ? new int[counters.length][][] : null;
        for (int field = 0; field < counters.length; field++) {
            snapshot[field] = new int[counters[field].length][];
            if (shown != null) {
                shown[field] = new int[counters[field].length][];
            }
            for (int row = 0; row < counters[field].length; row++) {
                snapshot[field][row] = counters[field][row].clone();
                if (shown != null) {
                    shown[field][row] = new int[counters[field][row].length];
                }
            }
        }
        mentions = fullPass ? new int[0][] : new int[][]{mentionChannels, mentionGroups};
        mentionSnapshot = new int[mentions.length][];
        for (int field = 0; field < mentions.length; field++) {
            mentionSnapshot[field] = mentions[field].clone();
        }
    }

    public static PurpleUnreadMirror start(int currentAccount,
            int[][] contacts, int[][] nonContacts, int[][] bots, int[][] channels,
            int[][] groups, int[][] communities) {
        return new PurpleUnreadMirror(currentAccount, true, contacts, nonContacts,
                bots, channels, groups, communities, null, null);
    }

    public static PurpleUnreadMirror start(int currentAccount,
            int[][] contacts, int[][] nonContacts, int[][] bots, int[][] channels,
            int[][] groups, int[][] communities, int[] mentionChannels, int[] mentionGroups) {
        return new PurpleUnreadMirror(currentAccount, false, contacts, nonContacts,
                bots, channels, groups, communities, mentionChannels, mentionGroups);
    }

    public void after(long dialogId) {
        after(dialogId, true);
    }

    public boolean counted(long dialogId) {
        return PurpleGate.countedInTotals(currentAccount, dialogId);
    }

    public void after(long dialogId, boolean countUnshown) {
        after(dialogId, countUnshown, counted(dialogId));
    }

    public void after(long dialogId, boolean countUnshown, boolean counted) {
        final boolean visible = counted
                && (shown == null && !countUnshown || PurpleGate.shown(currentAccount, dialogId));
        if (!counted) {
            uncounted++;
        } else if (shown == null && countUnshown && !visible) {
            unshown++;
        }
        for (int field = 0; field < counters.length; field++) {
            for (int row = 0; row < counters[field].length; row++) {
                for (int cell = 0; cell < counters[field][row].length; cell++) {
                    final int delta = counters[field][row][cell] - snapshot[field][row][cell];
                    if (delta != 0) {
                        if (!counted) {
                            counters[field][row][cell] = snapshot[field][row][cell];
                        } else if (shown != null && visible) {
                            shown[field][row][cell] += delta;
                        }
                    }
                    snapshot[field][row][cell] = counters[field][row][cell];
                }
            }
        }
        for (int field = 0; field < mentions.length; field++) {
            for (int cell = 0; cell < mentions[field].length; cell++) {
                if (!counted && mentions[field][cell] != mentionSnapshot[field][cell]) {
                    mentions[field][cell] = mentionSnapshot[field][cell];
                }
                mentionSnapshot[field][cell] = mentions[field][cell];
            }
        }
    }

    public int[][] pick(boolean isMain, int[][] field) {
        if (isMain && shown != null) {
            for (int index = 0; index < counters.length; index++) {
                if (counters[index] == field) {
                    return shown[index];
                }
            }
        }
        return field;
    }

    int[][] bucket(int index) {
        return counters[index];
    }

    public int uncounted() {
        return uncounted;
    }

    public int unshown() {
        return unshown;
    }
}
