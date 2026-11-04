/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncReader {
    static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;

    static final class RecordReceipt {
        private static final java.util.concurrent.atomic.AtomicLong observations=new java.util.concurrent.atomic.AtomicLong();
        static long observation(){return observations.get();}
        final long observation;
        final PurpleSyncChatOrigin origin;
        final Peer chat;
        final int id, date;
        final long documentId, editDate;
        final String hash, family;
        private final byte[] response, body;
        private RecordReceipt(PurpleSyncChatIO route,TLRPC.Message message,byte[] response,byte[] body,String family) {
            observation=observations.incrementAndGet();
            origin=route.origin;chat=route.chat;id=message.id;date=message.date;
            documentId=message.media.document.id;editDate=message.edit_date;this.family=family;
            this.response=response.clone();this.body=body.clone();hash=PurpleSyncRetainedFiles.hash(body);
        }
        byte[] bytes() { return body.clone(); }
        byte[] response() { return response.clone(); }
        private static TLRPC.Message exact(PurpleSyncClient.Lookup lookup) {
            if (lookup==null || lookup.route==null || lookup.status!=PurpleSyncClient.LookupStatus.Found) return null;
            TLRPC.messages_Messages page=PurpleSyncRecordSnapshot.messages(lookup.response);
            if (page==null || lookup.route.lookup(page,null,lookup.message.id,m->lookup.message).status!=PurpleSyncClient.LookupStatus.Found) return null;
            TLRPC.Message message=page.messages.get(0);
            return message.getClass()==TLRPC.TL_message.class && message.date>0
                    && lookup.route.ownership(message)==Ownership.Own ? message : null;
        }
        static RecordReceipt seal(PurpleSyncClient.Lookup opening,PurpleSyncClient.Lookup finalRead,byte[] bytes) {
            try {
                TLRPC.Message a=exact(opening), b=exact(finalRead);
                if (a==null || b==null || bytes==null || bytes.length==0 || bytes.length>PurpleSyncReader.MAX_RECORD_BYTES
                        || !opening.route.origin.equals(finalRead.route.origin) || !opening.route.chat.equals(finalRead.route.chat)
                        || a.date!=b.date || a.id!=b.id || a.edit_date!=b.edit_date
                        || !PurpleSyncChatIO.same(opening.message,finalRead.message)
                        || a.media==null || b.media==null || a.media.getClass()!=TLRPC.TL_messageMediaDocument.class
                        || b.media.getClass()!=TLRPC.TL_messageMediaDocument.class || a.media.document==null || b.media.document==null
                        || a.media.document.getClass()!=TLRPC.TL_document.class || b.media.document.getClass()!=TLRPC.TL_document.class
                        || a.media.document.id!=b.media.document.id || a.media.document.size!=bytes.length || b.media.document.size!=bytes.length
                        || !java.util.Objects.equals(a.message,b.message)) return null;
                String first=name(a.media.document),last=name(b.media.document);
                if (!java.util.Objects.equals(first,last) || !PurpleSyncPost.CAPTION.equals(b.message)) return null;
                String family=PurpleSyncPost.RECORD_FILE_NAME.equals(last)?"Settings":PurpleSyncPost.PLAYLISTS_RECORD_FILE_NAME.equals(last)?"Playlists":null;
                if (family==null) return null;
                RecordReceipt result=new RecordReceipt(finalRead.route,b,finalRead.response,bytes,family);
                return "Ready".equals(PurpleSyncRecordCore.request("message", result.message().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).status)?result:null;
            } catch (Exception error) { return null; }
        }
        private static String name(TLRPC.Document document) {
            for (TLRPC.DocumentAttribute a:document.attributes) if(a.getClass()==TLRPC.TL_documentAttributeFilename.class)return a.file_name;
            return null;
        }
        JSONObject message() {
            return json("account",origin.accountKey(),"chat",chat.token(),"id",Integer.toString(id),"date",Integer.toString(date),
                    "ownership","Own","family",family,"bytes",PurpleSyncRetainedBases.encode(body));
        }
    }

    static final class Row {
        final int id;
        final int outcome;
        final long documentId;
        final long editDate;
        final byte[] bytes;
        final RecordReceipt receipt;

        Row(int id, int outcome, long documentId, long editDate, byte[] bytes) {
            this(id, outcome, documentId, editDate, bytes, null);
        }

        Row(int id, int outcome, long documentId, long editDate, byte[] bytes, RecordReceipt receipt) {
            this.receipt = receipt;
            this.id = id;
            this.outcome = outcome;
            this.documentId = documentId;
            this.editDate = editDate;
            this.bytes = bytes;
        }
    }

    interface Done {
        void onRead(List<Row> rows);
    }

    private final PurpleSyncClient.Operation operation;
    private final int[] ids;
    private final PurpleSyncTransport.Progress progress;
    private final Done done;
    private final List<Row> rows = new ArrayList<>();
    private int next;
    private boolean requesting;
    private int token;
    private Object download;

    PurpleSyncReader(PurpleSyncClient.Operation operation, int[] ids,
            PurpleSyncTransport.Progress progress, Done done) {
        this.operation = operation;
        this.ids = ids.clone();
        this.progress = progress;
        this.done = done;
    }

    void start() {
        operation.progress(progress, PurpleSyncTransport.Phase.Reading, 0,
                ids.length);
        readNext();
    }

    void cancel() {
        if (requesting) {
            requesting = false;
            operation.client.cancelRequest(token);
        }
        if (download != null) {
            final Object running = download;
            download = null;
            operation.client.cancelDownload(running);
        }
    }

    private void readNext() {
        if (next == ids.length) {
            done.onRead(Collections.unmodifiableList(new ArrayList<>(rows)));
            return;
        }
        if (!operation.proceed()) {
            return;
        }
        final int id = ids[next];
        requesting = true;
        token = operation.client.requestMessage(id,
                operation.guard(lookup -> onLookup(id, lookup)));
    }

    private void onLookup(int id, PurpleSyncClient.Lookup lookup) {
        requesting = false;
        if (lookup.status == PurpleSyncClient.LookupStatus.Failed) {
            operation.client.log("message " + id + " request failed: "
                    + lookup.error);
            complete(new Row(id, PurpleSyncInventory.REQUEST_FAILED, 0, 0, null));
            return;
        }
        if (lookup.status == PurpleSyncClient.LookupStatus.Missing) {
            complete(new Row(id, PurpleSyncInventory.VANISHED, 0, 0, null));
            return;
        }
        final PurpleSyncClient.Message message = lookup.message;
        if (message.id != id || !message.isMessage || !operation.client.contains(message)) {
            complete(new Row(id, PurpleSyncInventory.CHANGED, 0, 0, null));
            return;
        }
        final PurpleSyncCore.Page page = PurpleSyncCore.classifyHistoryPage(0,
                new int[] { message.id },
                new boolean[] { message.isMessage },
                new boolean[] { message.forwarded },
                new boolean[] { message.isDocument },
                new String[] { message.caption },
                new String[] { message.fileName });
        if (!page.isValid() || page.status != PurpleSyncCore.PageStatus.More) {
            operation.client.log("message " + id + " not classified: "
                    + page.error);
            complete(new Row(id, PurpleSyncInventory.INACCESSIBLE, 0, 0, null));
            return;
        }
        if (page.candidates.length != 1 || page.candidates[0] != id) {
            complete(new Row(id, PurpleSyncInventory.CHANGED, 0, 0, null));
            return;
        }
        final long documentId = message.documentId;
        final long editDate = message.editDate > 0 ? message.editDate : 0;
        if (message.size <= 0) {
            complete(new Row(id, PurpleSyncInventory.INVALID, documentId,
                    editDate, null));
            return;
        }
        if (message.size > MAX_RECORD_BYTES) {
            complete(new Row(id, PurpleSyncInventory.OVERSIZED, documentId,
                    editDate, null));
            return;
        }
        if (!operation.proceed()) {
            return;
        }
        download = operation.client.download(message,
                operation.guard(file -> onFile(lookup, file)));
    }

    private void onFile(PurpleSyncClient.Lookup opening, File file) {
        PurpleSyncClient.Message message = opening.message;
        download = null;
        final long documentId = message.documentId;
        final long editDate = message.editDate > 0 ? message.editDate : 0;
        final byte[] bytes = file != null
                ? readAtMost(file, MAX_RECORD_BYTES + 1) : null;
        final int outcome;
        if (bytes == null) {
            operation.client.log("message " + message.id
                    + " document not downloaded");
            outcome = PurpleSyncInventory.INACCESSIBLE;
        } else if (bytes.length > MAX_RECORD_BYTES) {
            outcome = PurpleSyncInventory.OVERSIZED;
        } else if (bytes.length != message.size) {
            operation.client.log("message " + message.id + " document has "
                    + bytes.length + " bytes, expected " + message.size);
            outcome = PurpleSyncInventory.INACCESSIBLE;
        } else {
            outcome = PurpleSyncInventory.FETCHED;
        }
        if(outcome!=PurpleSyncInventory.FETCHED) {
            complete(new Row(message.id,outcome,documentId,editDate,null));
            return;
        }
        if(!operation.proceed())return;
        requesting=true;
        token=operation.client.requestMessage(message.id,operation.guard(fresh->{
            requesting=false;
            boolean unchanged=fresh!=null && fresh.status==PurpleSyncClient.LookupStatus.Found
                    && operation.client.contains(fresh.message) && PurpleSyncChatIO.same(message,fresh.message);
            RecordReceipt receipt = unchanged && opening.route != null
                    ? RecordReceipt.seal(opening, fresh, bytes) : null;
            if (opening.route != null && receipt == null) unchanged = false;
            complete(new Row(message.id,unchanged?PurpleSyncInventory.FETCHED:PurpleSyncInventory.CHANGED,
                    documentId,editDate,unchanged?bytes:null,receipt));
        }));
    }

    private void complete(Row row) {
        rows.add(row);
        ++next;
        operation.progress(progress, PurpleSyncTransport.Phase.Reading, next,
                ids.length);
        readNext();
    }

    static byte[] readAtMost(File file, int limit) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream input = new FileInputStream(file)) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream(
                    (int) Math.min(limit, Math.max(0, file.length())));
            final byte[] buffer = new byte[64 * 1024];
            int total = 0;
            while (total < limit) {
                final int read = input.read(buffer, 0,
                        Math.min(buffer.length, limit - total));
                if (read < 0) {
                    break;
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
