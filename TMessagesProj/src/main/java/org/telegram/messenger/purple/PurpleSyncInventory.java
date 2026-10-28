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
    public final PurpleSyncChatCore.Peer chat;
    final PurpleSyncChatOrigin origin;
    public final boolean scanComplete;
    final int[] ids;
    final int[] transport;
    final long[] documentIds;
    final long[] editDates;
    final byte[][] bytes;
    private final long totalBytes;
    private PurpleSyncChatMoveIO.RecordReceipt[] receipts;
    private PurpleSyncScanner.Result scanReceipt;
    long readSequence() { return scanReceipt==null?0:scanReceipt.readSequence; }
    boolean ownedScan() { return scanReceipt!=null && scanReceipt.matches(origin,chat,ids); }
    PurpleSyncChatMoveIO.RecordReceipt receipt(int i) { return receipts == null ? null : receipts[i]; }

    PurpleSyncInventory(long accountUserId, boolean scanComplete, int[] ids,
            int[] transport, long[] documentIds, long[] editDates,
            byte[][] bytes) {
        this(accountUserId,PurpleSyncChatCore.Peer.self(),null,scanComplete,ids,transport,documentIds,editDates,bytes);
    }

    PurpleSyncInventory(long accountUserId,PurpleSyncChatCore.Peer chat,PurpleSyncChatOrigin origin,
            boolean scanComplete,int[] ids,int[] transport,long[] documentIds,long[] editDates,byte[][] bytes) {
        if(chat==null || origin!=null && origin.userId!=accountUserId)throw new IllegalArgumentException("InventoryOrigin");
        this.chat=chat;this.origin=origin;
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

    boolean matchesState(byte[] state) {
        return PurpleSyncChatIO.ledgerMatches(chat,state);
    }

    boolean sameOrigin(PurpleSyncClient client) {
        return client!=null && accountUserId==client.userId() && chat.equals(client.chat())
                && java.util.Objects.equals(origin,client.origin());
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
        private final PurpleSyncChatCore.Peer chat;
        private final PurpleSyncChatOrigin origin;
        private int[] ids = new int[8];
        private int[] transport = new int[8];
        private long[] documentIds = new long[8];
        private long[] editDates = new long[8];
        private byte[][] bytes = new byte[8][];
        private int count;
        private final java.util.Map<Integer,PurpleSyncChatMoveIO.RecordReceipt> receipts = new java.util.HashMap<>();

        Builder fetched(PurpleSyncReader.Row row) {
            if (row.receipt != null) receipts.put(row.id, row.receipt);
            return fetched(row.id,row.documentId,row.editDate,row.bytes);
        }

        public Builder(long accountUserId) {
            this(accountUserId,PurpleSyncChatCore.Peer.self(),null);
        }

        Builder(long accountUserId,PurpleSyncChatCore.Peer chat,PurpleSyncChatOrigin origin) {
            this.accountUserId=accountUserId;this.chat=chat;this.origin=origin;
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

        PurpleSyncInventory build(PurpleSyncScanner.Result scan) {
            PurpleSyncInventory value=build(scan.complete);value.scanReceipt=scan;return value;
        }

        public PurpleSyncInventory build(boolean scanComplete) {
            PurpleSyncInventory value = new PurpleSyncInventory(accountUserId,chat,origin,scanComplete,
                    Arrays.copyOf(ids, count),
                    Arrays.copyOf(transport, count),
                    Arrays.copyOf(documentIds, count),
                    Arrays.copyOf(editDates, count),
                    Arrays.copyOf(bytes, count));
            value.receipts = new PurpleSyncChatMoveIO.RecordReceipt[count];
            for (int i=0;i<count;++i) value.receipts[i]=receipts.get(ids[i]);
            return value;
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
