package org.telegram.messenger.purple;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;
public final class LegacyCarryAutomaticCases {
    private static int checks;
    private static void require(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Map<String,String> snapshot(File root)throws Exception{
        Map<String,String> result=new TreeMap<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){
            for(Path p:(Iterable<Path>)paths::iterator){String key=root.toPath().relativize(p).toString();
                result.put(key,Files.isDirectory(p)?"dir":Base64.getEncoder().encodeToString(Files.readAllBytes(p)));}
        }
        return result;
    }
    public static void run(File parent)throws Exception{
        main(new String[]{new File(parent,"legacy-carry-automatic").getPath(),
                new File(PurpleAccountSyncStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalPath(),
                new File(PurpleSyncAutoEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalPath()});
    }
    public static void main(String[] args)throws Exception{
        PurpleCore.ensureLoaded();
        File expected=new File(args[1]).getCanonicalFile();
        require(new File(PurpleAccountSyncStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(expected),"actual selected store class origin");
        File original=new File(args[2]).getCanonicalFile();
        for(Class<?> c:new Class<?>[]{PurpleSyncAutoEngine.class,PurpleSyncAutoNetwork.class,PurpleSyncRunner.class})
            require(new File(c.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(original),"real qualified engine/network/runner origin");
        File parent=new File(args[0]);require(parent.mkdir(),"exclusive automatic fixture parent");
        for(boolean post:new boolean[]{false,true}){
            PurpleSyncMagicDevices.Device device=new PurpleSyncMagicDevices.Device(parent,"carry-auto-"+post,"fixture-carry-"+post);
            device.local(PurpleSyncMagicDevices.MINE);device.bind();
            PurpleSyncMagicDevices.Group group=new PurpleSyncMagicDevices.Group();
            PurpleSyncMagicScenarioCases.ownRecord(device,group,"carry initial record");
            PurpleSyncMagicDevices.Automatic port=new PurpleSyncMagicDevices.Automatic(device,group);
            port.client.editRefused=post;
            try{
                port.event(PurpleSyncAutoCore.Event.Enable);
                require(port.enabled&&port.failures.isEmpty(),"genuine automatic opt-in");
                int first=group.writes();
                port.saved(PurpleSyncMagicDevices.THEIRS);
                require(group.writes()==first+1&&port.failures.isEmpty(),"positive control makes one real engine transport write");
                File carry=new File(device.sync(),"carry.json");
                write(carry,"{}");
                Map<String,String> before=snapshot(device.sync());
                int writes=group.writes(),sends=port.sends;
                port.saved(PurpleSyncMagicDevices.MINE_COMMENTED);
                System.out.println("CARRY_AUTOMATIC_PROBE post="+post+" writes="+(group.writes()-writes)+" sends="+(port.sends-sends)+" reason="+port.reason);
                require(group.writes()==writes&&port.sends==sends,"malformed legacy carry must block automatic transport writes");
                require(snapshot(device.sync()).equals(before),"automatic refusal preserves all sync state carry retention and checkpoint bytes");
                require(port.reason==PurpleSyncAutoCore.Reason.StoreMismatch,"automatic engine reports refused store");
                require(port.failures.isEmpty(),"refusal is a status rather than an unhandled exception");
                require(Arrays.equals(PurpleSyncMagicDevices.MINE_COMMENTED,device.local()),"local user Save remains available");
                port.later();
                require(group.writes()==writes&&snapshot(device.sync()).equals(before),"later activation still refuses without changing evidence");
                boolean archived=false;for(String name:PurpleSyncRecoveryFiles.names(device.sync()))archived|=PurpleSyncRecoveryFiles.archiveName(name);
                require(!archived&&Files.isRegularFile(carry.toPath(),LinkOption.NOFOLLOW_LINKS),"automatic sync never sets carry aside or creates a dated folder");
            }finally{port.engine.close();}
        }
        System.out.println("LEGACY_CARRY_AUTOMATIC_CHECKS="+checks);
    }
}
