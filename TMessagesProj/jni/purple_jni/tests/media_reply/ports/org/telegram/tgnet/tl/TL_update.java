package org.telegram.tgnet.tl;

import org.telegram.tgnet.TLRPC;

public class TL_update {
    public static class TL_updateMessageID extends TLRPC.Update {
        public int id;
        public long random_id;
    }

    public static class TL_updateNewMessage extends TLRPC.Update {
        public TLRPC.Message message;
        public int pts;
        public int pts_count;
    }

    public static class TL_updateNewChannelMessage extends TLRPC.Update {
        public TLRPC.Message message;
        public int pts;
        public int pts_count;
    }

    public static class TL_updateNewScheduledMessage extends TLRPC.Update {
        public TLRPC.Message message;
    }

    public static class TL_updateQuickReplyMessage extends TLRPC.Update {
        public TLRPC.Message message;
    }
}
