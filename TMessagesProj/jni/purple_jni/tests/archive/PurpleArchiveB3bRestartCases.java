package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

public final class PurpleArchiveB3bRestartCases {
    static long leases() throws Exception {
        return on(()->{
            JSONObject query=PurpleArchiveCore.call("accessLeases",worker.store.arguments().put("after",""),worker.store.crypto());
            try(PurpleArchiveStore.Reader reader=worker.store.reader()){return reader.read(new PurpleArchiveCore.Statement(query.getJSONObject("statement"))).rows;}
        });
    }
    public static void main(String[] args)throws Exception{
        System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());boolean recover=args[2].equals("recover");File root=new File(args[1]);
        check(recover?root.isDirectory():root.mkdir(),"exclusive owned-reader restart fixture");ApplicationLoader.files=new File(root,"files");check(recover?ApplicationLoader.files.isDirectory():ApplicationLoader.files.mkdir(),"reader restart app files");
        ApplicationLoader.applicationContext=new android.content.Context();JSONObject saved=recover?new JSONObject(new String(Files.readAllBytes(new File(root,"seed.json").toPath()),StandardCharsets.UTF_8)):null;
        if(recover)PurpleArchivePlatformProvider.keys.put(saved.getString("alias"),new javax.crypto.spec.SecretKeySpec(PurpleArchiveCore.unbase64(saved.getString("key")),"AES"));
        UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"reader restart opens the original account offline");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));TLRPC.Message message=message(4100,"restart reader",79);
        if(!recover){
            PurpleArchiveB3bCases.retain(message,bytes(79));PurpleArchiveMediaAccess.Capability first=PurpleArchiveB3bCases.open(message),second=PurpleArchiveB3bCases.open(message);
            check(first!=null&&second!=null&&leases()==2,"process death leaves two durable reader leases");
            saved=on(()->new JSONObject().put("store",worker.store.binding.store).put("alias",worker.store.binding.alias()).put("key",PurpleArchiveCore.base64(PurpleArchivePlatformProvider.keys.get(worker.store.binding.alias()).getEncoded())));
            saved.put("first",first.token).put("second",second.token);Files.write(new File(root,"seed.json").toPath(),saved.toString().getBytes(StandardCharsets.UTF_8));
        }else{
            check(PurpleArchiveMediaProvider.lookup(PurpleArchiveMediaProvider.uri(saved.getString("first")))==null&&PurpleArchiveMediaProvider.lookup(PurpleArchiveMediaProvider.uri(saved.getString("second")))==null,"restart never revives the old opaque URI authority");
            await(()->{try{return leases()==0;}catch(Exception e){throw new RuntimeException(e);}},"startup durably releases every orphan lease");
            check(worker.store.binding.store.equals(saved.getString("store"))&&PurpleArchivePlatformProvider.creates.get()==0,"orphan release preserves the original store and key");
            PurpleArchiveMediaAccess.Capability fresh=PurpleArchiveB3bCases.open(message);check(fresh!=null&&!fresh.token.equals(saved.getString("first")),"a fresh explicit access obtains a new committed lease");fresh.close();
            await(()->{try{return leases()==0;}catch(Exception e){throw new RuntimeException(e);}},"fresh access also releases without leaving an orphan");
            on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();
        }
        System.out.println("ARCHIVE_B3B_RESTART_"+args[2]+"_CHECKS="+checks);
    }
}
