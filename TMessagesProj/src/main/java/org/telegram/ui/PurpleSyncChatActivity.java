package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.PurpleSyncChat;
import org.telegram.messenger.purple.PurpleSyncChatCore;
import org.telegram.messenger.purple.PurpleSyncChatScreen;
import org.telegram.messenger.purple.PurpleSyncChatMoveView;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

public final class PurpleSyncChatActivity extends UniversalFragment {
    private static final int REFRESH=0x70750110, SELF=REFRESH+1, EXISTING=REFRESH+2, CREATE=REFRESH+3, CONSENT=REFRESH+4, RESUME=REFRESH+5, CANCEL_SETUP=REFRESH+6, REVIEW_SETTINGS=REFRESH+7, CHANGE=REFRESH+8, FOLLOW_FILL=REFRESH+9, LOCAL_RESUME=REFRESH+10, CLEANUP_RETRY=REFRESH+11, CLEANUP_SKIP=REFRESH+12, RECOVERY_CREATE=REFRESH+13, RECOVERY_CHOOSE=REFRESH+14, RECOVERY_MARK=REFRESH+15, MOVE_RETRY=REFRESH+16, REMOVE_MARK=0x70750200;
    private PurpleSyncChatScreen.Control control;
    private PurpleSyncChatScreen.Frame frame;
    private PurpleSyncChatCore.Peer pending;
    private org.telegram.messenger.purple.PurpleAccountFiles.Identity identity;
    private volatile boolean visible;
    private org.telegram.messenger.purple.PurpleSyncChatSetupFlow setup;
    private org.telegram.messenger.purple.PurpleSyncChatSetupFlow.State setupState;
    private boolean limitShown;
    private Runnable rpcWatch;
    private org.telegram.messenger.purple.PurpleSyncChatMoveCleanup.Progress cleanupProgress;
    private org.telegram.messenger.purple.PurpleSyncChatFlows.Handle moveFlow;
    private Runnable moveWatch;
    private PurpleSyncChatMoveView.Feature moveFeature=PurpleSyncChatMoveView.Feature.Startup;
    private org.telegram.messenger.purple.PurpleSyncChatChange.State moveState;
    private boolean changePicker;
    private org.telegram.messenger.purple.PurpleSyncChatMarkActions markAction;

    public PurpleSyncChatActivity(int account) { setCurrentAccount(account); }
    @Override public boolean onFragmentCreate() {
        identity=org.telegram.messenger.purple.PurpleAccountFiles.capture(currentAccount);
        return identity!=null&&identity.current()&&super.onFragmentCreate();
    }
    static String text(String key,Object... args) {
        String value=LocaleController.getString("PurpleSyncChat"+key);
        return args.length==0?value:String.format(Locale.getDefault(),value,args);
    }
    private static String title(PurpleSyncChatScreen.Candidate candidate) {
        return candidate.chat.kind==PurpleSyncChatCore.PeerKind.Self?text("Saved"):candidate.title;
    }
    @Override protected CharSequence getTitle() { return text("Title"); }
    @Override public View createView(Context context) { return super.createView(context); }
    @Override public void onResume() {
        super.onResume();visible=true;
        if(rpcWatch!=null)rpcWatch.run();
        rpcWatch=org.telegram.messenger.purple.PurpleRpcRequests.observe(currentAccount,this::update);
        if(identity==null||!identity.current()){finishFragment();return;}
        if(control==null||!control.current())control=PurpleSyncChat.screen(currentAccount,()->visible&&identity!=null&&identity.current());
        if(moveWatch!=null)moveWatch.run();
        moveWatch=PurpleSyncChat.observeMove(currentAccount,()->visible&&identity!=null&&identity.current(),state->{if(moveFlow!=null&&moveFlow.current())return;recordMoveState(PurpleSyncChatMoveView.Feature.Startup,state);if(state.phase.equals("Complete"))reload();});
        openSetup();reload();
    }
    private void openSetup() {
        if(setup!=null)setup.close();
        setup=PurpleSyncChat.setup(currentAccount,()->visible&&identity!=null&&identity.current(),state->{
            if(!visible||setup==null||!setup.current())return;
            setupState=state;update();
            if(!state.busy&&!state.phase.equals("Opening")&&!state.phase.equals("Ready"))reload();
            if(state.phase.equals("Limit")&&!limitShown){
                limitShown=true;
                org.telegram.messenger.purple.PurpleSyncChatSetupFlow captured=setup;
                PurpleSyncChatSetupUI.limit(this,currentAccount,()->visible&&setup==captured&&captured.current(),new PurpleSyncChatSetupUI.Actions(){
                    public void tryAgain(){limitShown=false;captured.tryAgain();}
                    public void useSavedMessages(){limitShown=false;captured.useSavedMessages();}
                    public void cancel(){cancelSetup();}
                });
            }
        });
    }
    private void cancelSetup(){if(setup!=null)setup.close();setup=null;setupState=null;limitShown=false;if(visible)openSetup();update();}
    @Override public void onPause() {
        visible=false;if(moveFlow!=null)moveFlow.close();moveFlow=null;if(moveWatch!=null){moveWatch.run();moveWatch=null;}moveState=null;cleanupProgress=null;if(markAction!=null){markAction.close();markAction=null;}if(setup!=null){setup.close();setup=null;}setupState=null;if(rpcWatch!=null){rpcWatch.run();rpcWatch=null;}if(control!=null){control.close();control=null;}super.onPause();
    }
    @Override public void onFragmentDestroy() {
        visible=false;if(moveFlow!=null)moveFlow.close();moveFlow=null;if(moveWatch!=null){moveWatch.run();moveWatch=null;}moveState=null;cleanupProgress=null;if(markAction!=null){markAction.close();markAction=null;}if(setup!=null){setup.close();setup=null;}setupState=null;if(rpcWatch!=null){rpcWatch.run();rpcWatch=null;}if(control!=null){control.close();control=null;}super.onFragmentDestroy();
    }
    private void update() { if(visible&&listView!=null)listView.adapter.update(true); }
    private void reload() {
        PurpleSyncChatScreen.Control opening=control;
        if(opening==null||!visible)return;
        opening.refresh(value->{
            if(!visible||control!=opening)return;
            frame=value;update();
            if(pending!=null){PurpleSyncChatCore.Peer chat=pending;pending=null;if(changePicker){changePicker=false;inspectChange(chat,false);}else inspect(chat);}
        });
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader(text("Title")));
        org.telegram.messenger.purple.PurpleRpcRetry.Transient rpcWait=org.telegram.messenger.purple.PurpleRpcRequests.waiting(currentAccount);
        if(rpcWait!=null)items.add(UItem.asShadow(org.telegram.messenger.purple.PurpleRpcRequests.text(rpcWait)));
        if(frame==null)items.add(UItem.asShadow(text("Searching")));
        else {
            String line;
            switch(frame.status.phase) {
                case Unset:line=text("Unset");break;
                case Searching:line=text("Searching");break;
                case Lost:line=text("Lost");break;
                case Use:line=text("Using",frame.current!=null?title(frame.current):text("UnknownChat"));break;
                default:line=text("Paused",reason(frame.status.reason));
            }
            items.add(UItem.asShadow(line));
            if(frame.status.phase==PurpleSyncChat.Phase.Use&&frame.status.postingHeld)items.add(UItem.asShadow(reason(frame.status.reason)));
            if(frame.current!=null)items.add(UItem.asShadow(disclosure(frame.current)));
            for(PurpleSyncChatScreen.Refused refused:frame.refused)
                items.add(UItem.asShadow(text("RefusedMark",refused.title,refusal(refused.reason))));
            if(frame.newReaders) {
                String members=frame.current!=null&&frame.current.eligibility.readers.otherMembers!=null?frame.current.eligibility.readers.otherMembers.toString():text("UnknownCount");
                String baseline=frame.originalOtherMembers!=null?frame.originalOtherMembers.toString():text("UnknownCount");
                items.add(UItem.asShadow(text("NewReaders",frame.current!=null?title(frame.current):text("UnknownChat"),members,baseline)
                        +"\n"+text(frame.current!=null&&frame.current.hiddenPrehistory?"NewReadersFuture":"NewReadersEarlier")+"\n"+text("ReaderBlindSpot")));
            }
            if(org.telegram.messenger.purple.PurpleSyncChatMarkActions.ACTIVATED&&!frame.staleMarks.isEmpty()){
                items.add(UItem.asHeader(text("StaleMarks")));
                for(int i=0;i<frame.staleMarks.size();++i){
                    org.telegram.messenger.purple.PurpleSyncChatMarkActions.Row row=frame.staleMarks.get(i);
                    items.add(UItem.asButton(REMOVE_MARK+i,text("RemoveMarkRow",row.location.chat.kind==PurpleSyncChatCore.PeerKind.Self?text("Saved"):row.title,Integer.toString(row.location.markId))));
                }
            }
            if(frame.canFollowFill()){
                if(PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.FollowFill))items.add(UItem.asButton(FOLLOW_FILL,text(frame.status.resolution.action==PurpleSyncChatCore.Action.Follow?"Follow":"Fill")));
                else items.add(UItem.asShadow(text("FollowHeld")));
            }
            if(frame.locallyPaused())items.add(UItem.asButton(LOCAL_RESUME,text("LocalResume")));
            if(frame.status.reason.equals("ReaderConsent")&&frame.current!=null&&frame.current.allowed)
                items.add(UItem.asButton(CONSENT,text("ReviewReaders")));
            if(frame.canSetup&&(setupState==null||!setupState.busy&&!setupState.resumable)) {
                items.add(UItem.asButton(CREATE,text("Create")));
                items.add(UItem.asButton(SELF,text("Saved")));
                items.add(UItem.asButton(EXISTING,text("Choose")));
            } else if(org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.recovery(frame)){
                for(org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice choice:org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.actions(frame)){
                    int id=choice==org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.Create?RECOVERY_CREATE:choice==org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.Choose?RECOVERY_CHOOSE:RECOVERY_MARK;
                    items.add(UItem.asButton(id,text(choice==org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.Create?"RecoveryCreate":choice==org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.Choose?"RecoveryChoose":"RecoveryMark")));
                }
                items.add(UItem.asShadow(text("RecoveryAbout")));
                String held=org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED?org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.repairHeld(frame):"QualificationHeld";
                if(!held.isEmpty())items.add(UItem.asShadow(recoveryReason(held)));
            } else if(frame.canChange()){
                if(PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.Change))items.add(UItem.asButton(CHANGE,text("Change")));
                else items.add(UItem.asShadow(text("ChangeHeld")));
            }
            else if(frame.bound)items.add(UItem.asShadow(text("Bound")));
        }
        if(setupState!=null){
            if(setupState.busy){items.add(UItem.asShadow(text("SetupWorking")));items.add(UItem.asButton(CANCEL_SETUP,LocaleController.getString(org.telegram.messenger.R.string.Cancel)));}
            else if(setupState.resumable)items.add(UItem.asButton(RESUME,text("Resume")));
            if(setupState.canReview&&setupState.target!=null)items.add(UItem.asButton(CONSENT,text("ReviewReaders")));
            if(setupState.busy&&setupState.reason.equals("TelegramWait"))items.add(UItem.asShadow(text("SetupWait")));
            if(setupState.phase.equals("Held"))items.add(UItem.asShadow(text(setupState.reason.equals("TelegramWait")?"SetupWait":"SetupHeld")));
            if(setupState.phase.equals("Complete")){
                items.add(UItem.asShadow(text("SetupComplete")));
                items.add(UItem.asButton(REVIEW_SETTINGS,text("ReviewSettings")));
            }
        }
        if(moveState!=null){
            items.add(UItem.asShadow(text(moveState.phase.equals("Complete")?"ChangeComplete":moveState.phase.equals("Held")?"MoveHeld":"MoveWorking")));
            if(PurpleSyncChatMoveView.retry(moveFeature,moveState,moveLive()))items.add(UItem.asButton(MOVE_RETRY,LocaleController.getString(org.telegram.messenger.R.string.PurpleWriteActionRetry)));
        }
        if(cleanupProgress!=null){
            if(cleanupProgress.pending)items.add(UItem.asShadow(text("CleanupPending")));
            if(cleanupProgress.skipped)items.add(UItem.asShadow(text("CleanupSkipped")));
            if(cleanupProgress.uncopiedDestination)items.add(UItem.asShadow(text("InstallMoved")));
            if(cleanupProgress.pending&&cleanupProgress.canAct){
                items.add(UItem.asButton(CLEANUP_RETRY,text("CleanupRetry")));
                items.add(UItem.asButton(CLEANUP_SKIP,text("CleanupSkip")));
            }
        }
        items.add(UItem.asButton(REFRESH,text("Refresh")));
        items.add(UItem.asShadow(text("FirstPostHeld")));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(!visible||control==null||!control.current())return;
        if(org.telegram.messenger.purple.PurpleSyncChatMarkActions.ACTIVATED&&frame!=null&&item.id>=REMOVE_MARK&&item.id<REMOVE_MARK+frame.staleMarks.size()){removeMark(frame.staleMarks.get(item.id-REMOVE_MARK));return;}
        if(item.id==MOVE_RETRY){if(!PurpleSyncChatMoveView.retry(moveFeature,moveState,moveLive()))return;if(moveFlow!=null)moveFlow.retry();else PurpleSyncChat.retryMove(currentAccount);return;}
        if(item.id==CLEANUP_RETRY&&cleanupProgress!=null){cleanupProgress.retry();return;}
        if(item.id==CLEANUP_SKIP&&cleanupProgress!=null){
            org.telegram.messenger.purple.PurpleSyncChatMoveCleanup.Progress shown=cleanupProgress;
            new AlertDialog.Builder(getParentActivity()).setTitle(text("CleanupSkip")).setMessage(text("CleanupSkipAbout"))
                    .setPositiveButton(text("CleanupSkip"),(dialog,which)->{if(visible&&cleanupProgress==shown)shown.skip(true);})
                    .setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null).show();return;
        }
        if(item.id==REVIEW_SETTINGS&&setupState!=null&&setupState.phase.equals("Complete")){presentFragment(new PurpleSyncActivity(currentAccount));return;}
        if(item.id==FOLLOW_FILL&&PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.FollowFill)&&frame!=null&&frame.canFollowFill()){followFill();return;}
        if(item.id==LOCAL_RESUME&&frame!=null&&frame.locallyPaused()){control.pauseHere(false,saved->{if(visible){if(!saved)message(text("Changed"));reload();}});return;}
        if(item.id>=RECOVERY_CREATE&&item.id<=RECOVERY_MARK){
            if(!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED)return;
            if(item.id==RECOVERY_MARK){repairMark();return;}
            if(item.id==RECOVERY_CREATE){inspectChange(null,true);return;}
            changePicker=true;choose();return;
        }
        if(item.id==CHANGE&&frame!=null&&frame.canChange()){
            if(org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.recovery(frame)&&!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED)return;
            if(!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.Change))return;changeChoices();return;
        }
        if(item.id==CANCEL_SETUP){cancelSetup();return;}
        if(item.id==RESUME&&setup!=null){limitShown=false;setup.resume();return;}
        if(item.id==REFRESH)reload();
        else if(item.id==CONSENT&&setupState!=null&&setupState.canReview&&setupState.target!=null)inspect(setupState.target);
        else if(item.id==CONSENT&&frame!=null&&frame.current!=null)inspect(frame.current.chat);
        else if(frame!=null&&frame.canSetup) {
            if(item.id==SELF)inspect(PurpleSyncChatCore.Peer.self());
            else if(item.id==EXISTING){changePicker=false;choose();}
            else if(item.id==CREATE)confirmCreate();
        }
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
    private void choose() {
        final long self=UserConfig.getInstance(currentAccount).getClientUserId();
        Bundle args=new Bundle();args.putBoolean("onlySelect",true);args.putInt("dialogsType",0);args.putBoolean("allowSwitchAccount",false);
        DialogsActivity picker=new DialogsActivity(args);picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment,dids,message,param,notify,schedule,repeat,topics)->{
            if(identity==null||!identity.current())return false;
            if(dids==null||dids.size()!=1||topics!=null)return false;
            long did=dids.get(0).dialogId;
            if(DialogObject.isEncryptedDialog(did)||DialogObject.isFolderDialogId(did)||dids.get(0).topicId!=0)return false;
            if(did==self)pending=PurpleSyncChatCore.Peer.self();
            else if(did>0)pending=PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.User,did);
            else {
                TLRPC.Chat chat=MessagesController.getInstance(currentAccount).getChat(-did);
                if(chat==null)return false;
                pending=PurpleSyncChatCore.Peer.of(org.telegram.messenger.ChatObject.isChannel(chat)?PurpleSyncChatCore.PeerKind.Channel:PurpleSyncChatCore.PeerKind.Chat,-did);
            }
            fragment.finishFragment();return true;
        });
        presentFragment(picker);
    }
    private void inspect(PurpleSyncChatCore.Peer chat) {
        PurpleSyncChatScreen.Control opening=control;
        if(opening==null)return;
        opening.inspect(chat,candidate->{
            if(!visible||control!=opening)return;
            if(candidate==null){message(text("Changed"));reload();return;}
            if(!candidate.allowed){message(text("Refused",title(candidate),refusal(candidate.eligibility.refusal)));return;}
            AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("UseTitle",title(candidate)));
            dialog.setMessage(disclosure(candidate));
            dialog.setPositiveButton(text("Use"),(ignored,which)->opening.accept(candidate,true,true,selected->{
                if(!visible||control!=opening)return;
                if(selected!=null&&opening.frame()!=null&&opening.frame().canSetup&&setup!=null){limitShown=false;setup.begin(selected);}
                else {message(text(selected==null?"Changed":"ConsentSaved"));reload();}
            }));
            dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
        });
    }
    private void removeMark(org.telegram.messenger.purple.PurpleSyncChatMarkActions.Row row){
        PurpleSyncChatScreen.Control opening=control;
        if(!org.telegram.messenger.purple.PurpleSyncChatMarkActions.ACTIVATED||!visible||opening==null||!opening.current()||getParentActivity()==null)return;
        String chat=row.location.chat.kind==PurpleSyncChatCore.PeerKind.Self?text("Saved"):row.title;
        AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("RemoveMark"));
        dialog.setMessage(text("RemoveMarkAbout",chat,Integer.toString(row.location.markId)));
        dialog.setPositiveButton(text("RemoveMark"),(ignored,which)->{
            if(!org.telegram.messenger.purple.PurpleSyncChatMarkActions.ACTIVATED||!visible||control!=opening||!opening.current())return;
            if(markAction!=null)markAction.close();
            markAction=PurpleSyncChat.removeMark(currentAccount,row,()->visible&&control==opening&&opening.current(),state->{
                if(visible&&control==opening&&opening.current()){message(text(state.absent?"RemoveMarkComplete":"RemoveMarkHeld"));if(state.absent)reload();}
            });
        });
        dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
    }
    private void followFill(){
        if(!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.FollowFill))return;
        PurpleSyncChatScreen.Control opening=control;if(opening==null||!visible)return;
        opening.offerFollowFill("config",offer->{
            if(!visible||control!=opening||!opening.current()||getParentActivity()==null)return;
            if(offer==null){message(text("Changed"));reload();return;}
            Runnable accept=()->opening.acceptFollowFill(offer,true,selection->{
                if(!visible||control!=opening||!opening.current())return;
                if(selection==null){message(text("Changed"));reload();return;}
                moveFlow=PurpleSyncChat.followFill(currentAccount,selection,()->visible&&identity!=null&&identity.current(),state->{
                    if(visible&&control==opening&&opening.current()){recordMoveState(PurpleSyncChatMoveView.Feature.FollowFill,new org.telegram.messenger.purple.PurpleSyncChatChange.State(state.phase,state.reason,state.committed,state.cleanup));if(state.phase.equals("Complete"))reload();}
                });
            });
            if(!offer.requiresReaders){accept.run();return;}
            AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text(offer.action==PurpleSyncChatCore.Action.Follow?"Follow":"Fill"));
            dialog.setMessage(org.telegram.messenger.purple.PurpleSyncChatMoveConsent.disclosure(offer,PurpleSyncChatActivity::text));
            dialog.setPositiveButton(text(offer.action==PurpleSyncChatCore.Action.Follow?"Follow":"Fill"),(ignored,which)->accept.run());
            dialog.setNegativeButton(text("PauseHere"),(ignored,which)->{
                if(visible&&control==opening&&opening.current())opening.pauseHere(true,saved->{if(visible&&control==opening){if(!saved)message(text("Changed"));reload();}});
            });
            dialog.setNeutralButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
        });
    }
    private void changeChoices() {
        if(!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.Change))return;
        if(org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.recovery(frame)&&!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED)return;
        PurpleSyncChatScreen.Control opening=control;
        AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("Change"));
        dialog.setItems(new CharSequence[]{text("Create"),text("Saved"),text("Choose")},(ignored,which)->{
            if(!visible||control!=opening||!opening.current())return;
            if(which==0)inspectChange(null,true);else if(which==1)inspectChange(PurpleSyncChatCore.Peer.self(),false);
            else {changePicker=true;choose();}
        });
        dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
    }
    private void repairMark(){
        if(!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED||!visible||frame==null||!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.repairHeld(frame).isEmpty())return;
        PurpleSyncChatScreen.Frame shown=frame;
        new AlertDialog.Builder(getParentActivity()).setTitle(text("RecoveryMark")).setMessage(text("RecoveryMarkAbout"))
                .setPositiveButton(text("RecoveryMark"),(dialog,which)->{
                    if(!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED||!visible||frame!=shown||control==null||!control.current())return;
                    moveFlow=PurpleSyncChat.recover(currentAccount,shown,org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.RepairMark,null,()->visible,this::recoveryStatus);
                }).setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null).show();
    }
    private void recoveryStatus(org.telegram.messenger.purple.PurpleSyncChatChange.State state){
        if(!visible||!recordMoveState(PurpleSyncChatMoveView.Feature.Recovery,state))return;
        if(state.phase.equals("Warning"))message(text("RecoveryNewReaders"));
        else if(state.phase.equals("Held"))message(recoveryReason(state.reason));
        else if(state.phase.equals("Complete")&&(state.cleanup==null||!state.cleanup.pending))message(text("RecoveryComplete"));
    }
    private static String recoveryReason(String value){
        if(value.equals("QualificationHeld")||value.equals("TransportNotQualified"))return text("RecoveryQualificationHeld");
        if(value.equals("MissingLocalVersion"))return text("RecoveryMissingBase");
        if(value.equals("MarkNotProvenGone"))return text("RecoveryMarkUnproven");
        if(value.equals("ChatGone")||value.equals("ChatUnreadable"))return text("RecoveryUnreadable");
        if(value.equals("ReadersUnknown"))return text("ReadersUnknown");
        if(value.equals("ReaderRightsChanged"))return text("RecoveryRights");
        if(value.equals("ReaderConsent"))return text("ConsentNeeded");
        if(value.equals("Searching"))return text("RecoverySearching");
        if(value.equals("PendingStage"))return text("RecoveryPending");
        if(value.equals("OpeningChanged")||value.equals("AccountUnbound"))return text("Changed");
        return text("RecoveryHeld");
    }
    private void inspectChange(PurpleSyncChatCore.Peer target,boolean create) {
        PurpleSyncChatScreen.Frame recoveryFrame=org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.recovery(frame)?frame:null;
        if(recoveryFrame!=null?!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.Recovery):!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.Change))return;
        PurpleSyncChatScreen.Control opening=control;if(opening==null||!visible)return;
        opening.offerChange(target,create,offer->{
            if(!visible||control!=opening||getParentActivity()==null)return;
            if(offer==null){message(text("Changed"));reload();return;}
            android.widget.LinearLayout layout=new android.widget.LinearLayout(getParentActivity());layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            org.telegram.ui.Cells.CheckBoxCell cleanup=new org.telegram.ui.Cells.CheckBoxCell(getParentActivity(),1);
            String old=offer.source.kind==PurpleSyncChatCore.PeerKind.Self?text("Saved"):offer.sourceTitle;
            cleanup.setText(text("ChangeCleanup",old),"",offer.cleanupDefault,false);cleanup.setEnabled(offer.cleanupAvailable);
            cleanup.setOnClickListener(v->{if(offer.cleanupAvailable)cleanup.setChecked(!cleanup.isChecked(),true);});
            layout.addView(cleanup,new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            org.telegram.ui.Cells.CheckBoxCell oldMark=new org.telegram.ui.Cells.CheckBoxCell(getParentActivity(),1);
            oldMark.setText(text("ChangeOldMark",old),"",false,false);oldMark.setEnabled(offer.oldMarkAvailable);
            oldMark.setOnClickListener(v->{if(offer.oldMarkAvailable)oldMark.setChecked(!oldMark.isChecked(),true);});
            layout.addView(oldMark,new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("Change"));
            dialog.setMessage(PurpleSyncChatScreen.changeDisclosure(offer,PurpleSyncChatActivity::text));dialog.setView(layout);
            dialog.setPositiveButton(text("ChangeMove"),(ignored,which)->opening.acceptChange(offer,true,true,cleanup.isChecked(),oldMark.isChecked(),selection->{
                if(!visible||control!=opening||!opening.current())return;
                if(selection==null){message(text("Changed"));reload();return;}
                if(recoveryFrame!=null){
                    if(!org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ACTIVATED)return;
                    moveFlow=PurpleSyncChat.recover(currentAccount,recoveryFrame,create?org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.Create:org.telegram.messenger.purple.PurpleSyncChatMoveRecovery.ActionChoice.Choose,selection,()->visible,this::recoveryStatus);return;
                }
                moveFlow=PurpleSyncChat.change(currentAccount,selection,()->visible&&identity!=null&&identity.current(),state->{
                    if(visible&&control==opening&&opening.current()){recordMoveState(PurpleSyncChatMoveView.Feature.Change,state);if(state.phase.equals("Complete"))reload();}
                });
            }));
            dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
        });
    }
    private boolean moveLive(){
        return moveFlow!=null?moveFlow.current():moveFeature==PurpleSyncChatMoveView.Feature.Startup&&PurpleSyncChat.moveRetryAvailable(currentAccount);
    }
    private boolean recordMoveState(PurpleSyncChatMoveView.Feature feature,org.telegram.messenger.purple.PurpleSyncChatChange.State state){
        if(!visible)return false;
        moveFeature=feature;moveState=PurpleSyncChatMoveView.progress(state);cleanupProgress=moveState!=null?moveState.cleanup:null;update();
        if(moveState==null){message(text(feature==PurpleSyncChatMoveView.Feature.FollowFill?"FollowHeld":feature==PurpleSyncChatMoveView.Feature.Recovery?"RecoveryQualificationHeld":"ChangeHeld"));return false;}
        return true;
    }
    private void confirmCreate() {
        PurpleSyncChatScreen.Control opening=control;
        AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("Create"));dialog.setMessage(text("CreateAbout")+"\n\n"+text("Cloud",text("NewChannel")));
        dialog.setPositiveButton(text("Continue"),(ignored,which)->opening.create(true,selected->{if(visible&&control==opening){if(selected!=null&&setup!=null){limitShown=false;setup.begin(selected);}else{message(text("Changed"));reload();}}}));
        dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
    }
    private void message(String text) {
        if(!visible||getParentActivity()==null)return;
        new AlertDialog.Builder(getParentActivity()).setTitle(PurpleSyncChatActivity.text("Title")).setMessage(text)
                .setPositiveButton(LocaleController.getString(org.telegram.messenger.R.string.OK),null).show();
    }
    static String disclosure(PurpleSyncChatScreen.Candidate candidate) {
        return PurpleSyncChatScreen.disclosure(candidate,PurpleSyncChatActivity::text);
    }
    private static String refusal(PurpleSyncChatCore.Refusal value) { return text("Refusal"+value.name()); }
    private static String reason(String value) {
        if(value.equals("LocalPause"))return text("LocalPaused");
        if(value.equals("SetupPostingHeld"))return text("FirstPostHeld");
        if(value.equals("ReaderConsent"))return text("ConsentNeeded");
        if(value.equals("UnknownReaders"))return text("ReadersUnknown");
        if(value.equals("NewerMark"))return text("NewerMark");
        if(value.equals("FollowRequired")||value.equals("FillRequired"))return text("MoveHeld");
        return text("Retry");
    }
}
