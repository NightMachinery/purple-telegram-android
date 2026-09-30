/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.Arrays;

public final class PurpleSyncInventory {
    public static final int FETCHED = 0;
    public static final int VANISHED = 1;
    public static final int CHANGED = 2;
    public static final int OVERSIZED = 3;
    public static final int INACCESSIBLE = 4;
    public static final int REQUEST_FAILED = 5;
    public static final int CANCELLED = 6;
    public static final int INVALID = 7;

    public final long accountUserId;
    public final boolean scanComplete;
    final int[] ids;
    final int[] transport;
    final long[] documentIds;
    final long[] editDates;
    final byte[][] bytes;
    private final long totalBytes;

    PurpleSyncInventory(long accountUserId, boolean scanComplete, int[] ids,
            int[] transport, long[] documentIds, long[] editDates,
            byte[][] bytes) {
        final int count = ids.length;
        if (transport.length != count || documentIds.length != count
                || editDates.length != count || bytes.length != count) {
            throw new IllegalArgumentException("inventory arrays differ in length");
        }
        long total = 0;
        for (int i = 0; i != count; ++i) {
            if (transport[i] < FETCHED || transport[i] > INVALID) {
                throw new IllegalArgumentException("unknown transport outcome");
            }
            if (transport[i] == FETCHED) {
                if (bytes[i] == null) {
                    throw new IllegalArgumentException("fetched record without bytes");
                }
                total += bytes[i].length;
            } else if (bytes[i] != null) {
                throw new IllegalArgumentException("bytes for a record not fetched");
            }
        }
        this.accountUserId = accountUserId;
        this.scanComplete = scanComplete;
        this.ids = ids;
        this.transport = transport;
        this.documentIds = documentIds;
        this.editDates = editDates;
        this.bytes = bytes;
        this.totalBytes = total;
    }

    public int count() {
        return ids.length;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public int id(int index) {
        return ids[index];
    }

    public int transport(int index) {
        return transport[index];
    }

    public String summary() {
        return "user=" + accountUserId + " scanComplete=" + scanComplete
                + " records=" + count() + " bytes=" + totalBytes;
    }

    public static final class Builder {
        private final long accountUserId;
        private int[] ids = new int[8];
        private int[] transport = new int[8];
        private long[] documentIds = new long[8];
        private long[] editDates = new long[8];
        private byte[][] bytes = new byte[8][];
        private int count;

        public Builder(long accountUserId) {
            this.accountUserId = accountUserId;
        }

        public Builder fetched(int id, long documentId, long editDate,
                byte[] record) {
            if (record == null) {
                throw new IllegalArgumentException("fetched record without bytes");
            }
            return add(id, FETCHED, documentId, editDate, record);
        }

        public Builder failed(int id, int outcome, long documentId,
                long editDate) {
            if (outcome <= FETCHED || outcome > INVALID) {
                throw new IllegalArgumentException("not a failed outcome");
            }
            return add(id, outcome, documentId, editDate, null);
        }

        public PurpleSyncInventory build(boolean scanComplete) {
            return new PurpleSyncInventory(accountUserId, scanComplete,
                    Arrays.copyOf(ids, count),
                    Arrays.copyOf(transport, count),
                    Arrays.copyOf(documentIds, count),
                    Arrays.copyOf(editDates, count),
                    Arrays.copyOf(bytes, count));
        }

        private Builder add(int id, int outcome, long documentId,
                long editDate, byte[] record) {
            if (count == ids.length) {
                final int grown = count * 2;
                ids = Arrays.copyOf(ids, grown);
                transport = Arrays.copyOf(transport, grown);
                documentIds = Arrays.copyOf(documentIds, grown);
                editDates = Arrays.copyOf(editDates, grown);
                bytes = Arrays.copyOf(bytes, grown);
            }
            ids[count] = id;
            transport[count] = outcome;
            documentIds[count] = documentId;
            editDates[count] = editDate;
            bytes[count] = record;
            ++count;
            return this;
        }
    }
}
