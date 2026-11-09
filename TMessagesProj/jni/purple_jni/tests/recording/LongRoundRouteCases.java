package org.telegram.messenger.purple;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
public class LongRoundRouteCases {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        UserConfig.identity = 9; UserConfig.active = true;
        final SendMessageChatArguments opening = PurpleRecordingSendAs.freeze(1, 9, 100, null);
        final SendMessageChatArguments first = PurpleRecordingSendAs.copyFrozen(1, 9, 100, opening);
        final SendMessageChatArguments next = PurpleRecordingSendAs.copyFrozen(1, 9, 100, opening);
        check(first != opening && next != first, "separate per-part queue keys");
        check(!PurpleRecordingSendAs.reject(1, 100, first), "captured user and target accepted");
        check(PurpleRecordingSendAs.copyFrozen(1, 9, 200, opening) == null, "target cannot drift while preparing");
        int[] prepared = {0}, queued = {0}, failed = {0};
        PurpleRecordingRegistry.bin.add("long");
        PurpleRecordingDelivery.sendRetained("long", 1, 9, 100, first, () -> prepared[0]++, () -> queued[0]++, () -> failed[0]++);
        check(prepared[0] == 1 && queued[0] == 0, "prepare is not queue acknowledgement");
        PurpleRecordingDelivery.sendRetained("long", 1, 9, 100, next, () -> prepared[0]++, () -> queued[0]++, () -> failed[0]++);
        check(prepared[0] == 1 && failed[0] == 1, "one pending ticket for original");
        final SendMessagesHelper.SendMessageParams params = new SendMessagesHelper.SendMessageParams();
        params.sendMessageChatArguments = first; params.peer = 100;
        final TLRPC.Message message = new TLRPC.Message(); message.id = message.local_id = -99;
        PurpleRecordingDelivery.queued(1, params, message);
        PurpleRecordingDelivery.queued(1, params, message);
        check(queued[0] == 1, "exactly one stored acknowledgement");
        check(PurpleRecordingRegistry.bin.contains("long") && PurpleRecordingRegistry.ack == 0, "successful copy never removes full original");
        PurpleRecordingDelivery.sendRetained("long", 1, 9, 100, next, () -> prepared[0]++, () -> queued[0]++, () -> failed[0]++);
        check(prepared[0] == 2, "only acknowledged part admits next");
        PurpleRecordingDelivery.revoke("long");
        check(!PurpleRecordingDelivery.allowed(next), "timeout revokes delayed native enqueue");
        final SendMessageChatArguments late = PurpleRecordingSendAs.copyFrozen(1, 9, 100, opening);
        PurpleRecordingRegistry.pendingDefer = true;
        PurpleRecordingDelivery.sendRetained("long", 1, 9, 100, late, () -> prepared[0]++, () -> queued[0]++, () -> failed[0]++);
        UserConfig.identity = 10;
        PurpleRecordingRegistry.pending();
        check(prepared[0] == 2 && failed[0] == 2, "slot replacement before retained lookup rejects send");
        check(PurpleRecordingSendAs.copyFrozen(1, 9, 100, opening) == null, "slot replacement cannot refresh identity");
        check(PurpleRecordingRegistry.bin.contains("long"), "unknown and rejected sends preserve original");
        System.out.println("LONG_ROUND_ROUTE_CHECKS=" + checks);
    }
}
