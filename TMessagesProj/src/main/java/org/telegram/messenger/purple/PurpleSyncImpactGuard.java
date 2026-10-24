package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class PurpleSyncImpactGuard {
    private final PurpleAccountEditor.Current identity;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final List<PurpleGlobalChoiceFiles.Snapshot> files=new ArrayList<>();
    private final LinkedHashMap<File,PurpleGlobalChoiceFiles.Mark> directories=new LinkedHashMap<>();
    PurpleSyncImpactGuard(File root,List<File> authorities,PurpleGlobalChoiceFiles.Physical physical,PurpleAccountEditor.Current identity) throws Exception {
        this(root,authorities,null,physical,identity);
    }
    PurpleSyncImpactGuard(File root,List<File> authorities,File targetDirectory,PurpleGlobalChoiceFiles.Physical physical,PurpleAccountEditor.Current identity) throws Exception {
        this.identity=identity;this.physical=physical;
        for(File file:authorities) {
            if(!file.getAbsolutePath().startsWith(root.getAbsolutePath()+File.separator))throw new IllegalStateException("impact path outside root");
            for(File parent=file.getParentFile();parent!=null&&!parent.equals(root.getParentFile());parent=parent.getParentFile())
                if(!directories.containsKey(parent))directories.put(parent,physical.mark(parent,true));
            files.add(new PurpleGlobalChoiceFiles.Snapshot(file,false,PurpleGlobalChoiceFiles.LIMIT,physical));
        }
        for(File directory=targetDirectory;directory!=null&&!directory.equals(root.getParentFile());directory=directory.getParentFile())
            if(!directories.containsKey(directory))directories.put(directory,physical.mark(directory,true));
        if(!current())throw new IllegalStateException("impact context changed");
    }
    boolean acceptSetupDirectories(java.util.Map<File,PurpleGlobalChoiceFiles.Mark> created) {
        try {
            if(!identity.current())return false;
            for(PurpleGlobalChoiceFiles.Snapshot file:files)if(!file.current(physical))return false;
            for(java.util.Map.Entry<File,PurpleGlobalChoiceFiles.Mark> entry:directories.entrySet()) {
                PurpleGlobalChoiceFiles.Mark now=physical.mark(entry.getKey(),true);
                if(entry.getValue().same(now))continue;
                PurpleGlobalChoiceFiles.Mark owned=created.get(entry.getKey());
                if(!entry.getValue().missing()||owned==null||!owned.same(now))return false;
                entry.setValue(now);
            }
            return current();
        }catch(Exception e){return false;}
    }
    boolean current() {
        try {
            if(!identity.current())return false;
            for(java.util.Map.Entry<File,PurpleGlobalChoiceFiles.Mark> entry:directories.entrySet())if(!entry.getValue().same(physical.mark(entry.getKey(),true)))return false;
            for(PurpleGlobalChoiceFiles.Snapshot file:files)if(!file.current(physical))return false;
            return identity.current();
        } catch(Exception e){return false;}
    }
}
