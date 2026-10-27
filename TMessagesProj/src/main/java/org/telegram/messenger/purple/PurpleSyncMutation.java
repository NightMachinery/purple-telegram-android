package org.telegram.messenger.purple;

final class PurpleSyncMutation {
    final PurpleAccountEditor.Session editor;
    final PurpleConfigImpact.Input impact;
    private final PurpleAccountEditor.Current route;
    private final PurpleAccountEditorModel model;
    private final PurpleSyncImpactGuard effects;
    private volatile java.util.function.BooleanSupplier admission=()->true;

    PurpleSyncMutation(PurpleAccountEditor.Session editor, PurpleAccountEditor.Current route) {
        this.editor = editor; this.route = route; model = null; effects=null;
        impact = editor.impact;
    }
    PurpleSyncMutation(PurpleAccountEditorModel model, PurpleConfigImpact.Input impact, PurpleAccountEditor.Current route) { this(model,impact,route,null); }
    PurpleSyncMutation(PurpleAccountEditorModel model,PurpleConfigImpact.Input impact,PurpleAccountEditor.Current route,PurpleSyncImpactGuard effects) {
        this.model=model;this.impact=impact;this.route=route;this.effects=effects;editor=null;model.impact(impact);
    }
    private PurpleSyncMutation(PurpleSyncMutation source,java.util.function.BooleanSupplier guard) {
        editor=source.editor;model=source.model;impact=source.impact;route=source.route;effects=source.effects;
        admission=()->source.admission.getAsBoolean()&&guard.getAsBoolean();
    }
    PurpleSyncMutation scoped(java.util.function.BooleanSupplier guard) { return new PurpleSyncMutation(this,guard); }
    synchronized void require(java.util.function.BooleanSupplier guard) {java.util.function.BooleanSupplier previous=admission;admission=()->previous.getAsBoolean()&&guard.getAsBoolean();}
    boolean effectCurrent() { return admission.getAsBoolean()&&route.current()&&(editor!=null?editor.effects.current():effects==null||effects.current()); }
    boolean current() { return effectCurrent() && (editor!=null?editor.current():model.current()); }
    boolean acceptSetupDirectories(java.util.Map<java.io.File,PurpleGlobalChoiceFiles.Mark> created) {
        return route.current()&&(editor!=null?editor.acceptSetupDirectories(created):model.acceptSetupDirectories(created)&&(effects==null||effects.acceptSetupDirectories(created)));
    }
    boolean replace(byte[] bytes, boolean imported) {
        if(editor==null) {
            String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(!java.util.Arrays.equals(bytes,PurpleAccountEditorModel.encode(text))||!current())return false;
            PurpleAccountEditorModel.Preview preview=model.previewSync(text,imported);
            return preview!=null&&model.save(preview,()->admission.getAsBoolean()&&route.current()).ok;
        }
        PurpleAccountEditor.Preview preview = editor.previewSync(bytes, imported);
        if (preview == null || !current()) return false;
        PurpleAccountEditor.Outcome result = editor.saveSync(preview, ()->admission.getAsBoolean()&&route.current(), imported);
        return result.ok;
    }
}
