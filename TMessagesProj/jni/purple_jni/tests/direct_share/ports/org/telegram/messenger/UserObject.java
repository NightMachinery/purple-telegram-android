package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public final class UserObject {public static boolean isDeleted(TLRPC.User u){return u==null||u.deleted;}}
