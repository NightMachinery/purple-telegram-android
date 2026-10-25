package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

public final class PurpleArchiveB3aRestartCases {
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); boolean recover = args[2].equals("recover");
        check(recover ? root.isDirectory() : root.mkdir(), "restart root");
        ApplicationLoader.files = new File(root, "files"); check(recover ? ApplicationLoader.files.isDirectory() : ApplicationLoader.files.mkdir(), "restart app files");
        JSONObject expected = recover ? new JSONObject(new String(Files.readAllBytes(new File(root,"seed-state.json").toPath()),StandardCharsets.UTF_8)) : null;
        if (recover) PurpleArchivePlatformProvider.keys.put(expected.getString("alias"),new javax.crypto.spec.SecretKeySpec(PurpleArchiveCore.unbase64(expected.getString("key")),"AES"));
        UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"restart opens original store offline");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        if (!recover) {
            JSONObject saved=on(()->{
                TLRPC.Message partial=message(2300,"partial",131089),full=message(2301,"covered",131089);
                PurpleArchiveStaging a=stage(partial,new AtomicBoolean(true));a.piece(0,Arrays.copyOf(bytes(131089),65536));
                PurpleArchiveStaging b=stage(full,new AtomicBoolean(true));add(b,bytes(131089));
                PurpleArchiveStaging unadmitted=stage(message(2302,"unvalidated process death",97),new AtomicBoolean(true));unadmitted.piece(0,bytes(97));
                return new JSONObject().put("partial",a.id).put("full",b.id).put("unadmitted",unadmitted.id).put("store",worker.store.binding.store).put("alias",worker.store.binding.alias())
                    .put("key",PurpleArchiveCore.base64(PurpleArchivePlatformProvider.keys.get(worker.store.binding.alias()).getEncoded()));
            });
            Files.write(new File(root,"seed-state.json").toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));
            check(!complete(message(2301,"covered",131089),"document_11301"),"process-death seed leaves covered media Staged before finalization");
        } else {
            TLRPC.Message full=message(2301,"covered",131089);
            await(()->{try{return complete(full,"document_11301");}catch(Exception e){throw new RuntimeException(e);}},"startup finalizes fully owned covered staging without a network request");
            JSONObject saved=expected;
            on(()->{
                check(worker.store.binding.store.equals(saved.getString("store")),"restart keeps the same store and login anchor");
                PurpleArchiveStaging partial=stage(message(2300,"partial",131089),new AtomicBoolean(true));
                check(partial.id.equals(saved.getString("partial")),"partial stream resumes the original encrypted binding across a real process restart");
                add(partial,bytes(131089));check(partial.finish(),"partial restart finishes from observed bytes");canonical(partial,bytes(131089));return null;
            });
            check(!complete(message(2302,"unvalidated process death",97),"document_11302"),"unvalidated full coverage remains incomplete after process death");
            check(on(()->worker.store.hasGaps()&&!worker.store.directory.child("staging",true).child("stream",true).file(saved.getString("unadmitted")).exists()),"recovery gaps and removes covered staging without a validator");
            check(PurpleArchivePlatformProvider.creates.get()==0,"restart never replaces an existing account key");
        }
        System.out.println("ARCHIVE_B3A_RESTART_"+args[2]+"_CHECKS="+checks);
    }
}
