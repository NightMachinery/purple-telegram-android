package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;

final class PurpleGlobalChoiceProgress {
    interface Current { boolean current(); }
    interface Loader<T> { T load(byte[] settings, byte[] state); }
    private static final class Ownership {
        final File file;
        final boolean directory;
        final PurpleGlobalChoiceFiles.Mark mark;
        Ownership(File file, boolean directory, PurpleGlobalChoiceFiles.Physical physical) throws Exception {
            this.file = file; this.directory = directory; mark = physical.mark(file,directory);
        }
        boolean current(PurpleGlobalChoiceFiles.Physical physical) throws Exception {
            PurpleGlobalChoiceFiles.Mark now = physical.mark(file,directory);
            return mark.identity.equals(now.identity) && mark.mode == now.mode;
        }
    }
    final PurpleGlobalChoiceModel.ActivationSource source;
    private final Current context;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final ArrayList<Ownership> ownership = new ArrayList<>();
    private boolean retired;
    private PurpleGlobalChoiceProgress(File root, File file, PurpleGlobalChoiceModel.ActivationSource source,
            PurpleGlobalChoiceFiles.Physical physical, Current context) throws Exception {
        this.source = source; this.physical = physical; this.context = context;
        if (!context.current() || !file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)) throw new IllegalStateException("state source changed");
        for (File p=file.getParentFile();p!=null&&!p.equals(root.getParentFile());p=p.getParentFile()) ownership.add(new Ownership(p,true,physical));
        Ownership primary = new Ownership(file,false,physical);
        ownership.add(primary);
        if (source.defaults && !primary.mark.missing()) throw new IllegalStateException("default source acquired a creator");
        if (source.good || source.defaults) {
            Ownership good = new Ownership(new File(file.getPath()+".good"),false,physical);
            ownership.add(good);
            if (source.good && good.mark.missing() || source.defaults && !good.mark.missing()) throw new IllegalStateException("recovery ownership changed");
        }
        if (!current()) throw new IllegalStateException("state source changed");
    }
    boolean current() {
        if (retired) return false;
        try {
            if (!context.current()) { retired=true; return false; }
            for (Ownership input:ownership) if (!input.current(physical)) { retired=true; return false; }
            if (!context.current()) { retired=true; return false; }
            return true;
        } catch (Exception e) { retired=true; return false; }
    }
    PurpleGlobalChoiceModel.ActivationSource forState(byte[] observedPrimary) {
        return current() ? new PurpleGlobalChoiceModel.ActivationSource(observedPrimary,source.settings,source.good,source.defaults) : null;
    }
    static final class Use {
        final PurpleGlobalChoiceModel.ActivationSource source;
        private final PurpleGlobalChoiceProgress authority;
        private final Current loadContext;
        private boolean retired;
        private Use(PurpleGlobalChoiceProgress authority, PurpleGlobalChoiceModel.ActivationSource source, Current loadContext) {
            this.authority=authority; this.source=source; this.loadContext=loadContext;
        }
        boolean current() {
            if (retired) return false;
            if (!authority.current() || !loadContext.current()) { retired=true; return false; }
            return true;
        }
        PurpleGlobalChoiceProgress authority() { return current() ? authority : null; }
        <T> T resolve(byte[] state, Loader<T> loader) {
            if (!current()) return null;
            T result=PurpleGlobalChoiceProgress.resolve(source,state,loader);
            return current() ? result : null;
        }
    }
    Use forStateUse(byte[] observedPrimary, Current loadContext) {
        PurpleGlobalChoiceModel.ActivationSource derived=forState(observedPrimary);
        if (derived == null) return null;
        Use use=new Use(this,derived,loadContext);
        return use.current() ? use : null;
    }
    static PurpleGlobalChoiceProgress accepted(File root, File file, PurpleGlobalChoiceModel.ActivationSource source,
            PurpleGlobalChoiceFiles.Physical physical, Current context) {
        try { return new PurpleGlobalChoiceProgress(root,file,source,physical,context); }
        catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
    static <T> T resolve(PurpleGlobalChoiceModel.ActivationSource source, byte[] state, Loader<T> loader) {
        return loader.load(source.settings,state);
    }
}
