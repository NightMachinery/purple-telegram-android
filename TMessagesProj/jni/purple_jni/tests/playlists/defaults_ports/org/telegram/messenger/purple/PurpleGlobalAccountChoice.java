package org.telegram.messenger.purple;
import java.io.File;import java.util.*;
public class PurpleGlobalAccountChoice {
 public interface Current {boolean current();}
 static final PurpleGlobalChoiceFiles.Physical PHYSICAL=NativeWriterFixture.physical();
 private static List<PurpleGlobalChoiceModel.Account> roster(){List<PurpleGlobalChoiceModel.Account> list=new ArrayList<>();for(PurpleAccountFiles.Identity id:PurpleAccountOwner.signedIn())list.add(new PurpleGlobalChoiceModel.Account(id.slot,id.key,id.lifetime,id,id,id));return list;}
 public static final class Session {
        public final List<PurpleAccountFiles.Identity> accounts;
        public final String owner, global;
        private final PurpleAccountFiles.Identity origin;
        private final List<PurpleGlobalChoiceModel.Account> roster;
        private final Current ui;
        private final long revision, role, generation, nativeEpoch;
        private final int gate;
        private final File root;
        Session(int account, Current ui) {
            this.ui = ui;
            origin = PurpleAccountFiles.capture(account);
            accounts = PurpleAccountOwner.signedIn();
            roster = roster();
            owner = PurpleAccountOwner.owner(); global = PurpleAccountOwner.global();
            revision = PurpleAccountOwner.revision(); role = PurpleAccountOwner.roleEpoch();
            generation = PurpleAccountSettings.generation(); nativeEpoch = PurpleAccountSettings.epochNative();
            gate = PurpleGate.generation(); root = PurpleAccountFiles.root();
        }
        private boolean identityCurrent() {
            return ui.current() && origin != null && origin.current()
                    && root.equals(PurpleAccountFiles.root()) && PurpleGlobalChoiceModel.sameRoster(roster, roster());
        }
        public boolean current() { return currentAfterAccountRefresh(false); }
        boolean currentAfterAccountRefresh(boolean refreshed) {
            int steps = refreshed ? 1 : 0;
            return identityCurrent() && Objects.equals(owner, PurpleAccountOwner.owner()) && Objects.equals(global, PurpleAccountOwner.global())
                    && revision == PurpleAccountOwner.revision() && role == PurpleAccountOwner.roleEpoch()
                    && generation + steps == PurpleAccountSettings.generation() && nativeEpoch + steps == PurpleAccountSettings.epochNative()
                    && gate == PurpleGate.generation();
        }
}

 static Session begin(int account,Current ui,String[] reason){if(!ui.current())return null;Session session=new Session(account,ui);return session.current()?session:null;}
}
