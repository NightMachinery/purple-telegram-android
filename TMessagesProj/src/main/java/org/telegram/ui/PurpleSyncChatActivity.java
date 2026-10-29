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
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

public final class PurpleSyncChatActivity extends UniversalFragment {
    private static final int REFRESH=0x70750110, SELF=REFRESH+1, EXISTING=REFRESH+2, CREATE=REFRESH+3, CONSENT=REFRESH+4, RESUME=REFRESH+5, CANCEL_SETUP=REFRESH+6, REVIEW_SETTINGS=REFRESH+7, CHANGE=REFRESH+8, FOLLOW_FILL=REFRESH+9, LOCAL_RESUME=REFRESH+10, REMOVE_MARK=0x70750200;
    private PurpleSyncChatScreen.Control control;
    private PurpleSyncChatScreen.Frame frame;
    private PurpleSyncChatCore.Peer pending;
    private org.telegram.messenger.purple.PurpleAccountFiles.Identity identity;
    private volatile boolean visible;
    private org.telegram.messenger.purple.PurpleSyncChatSetupFlow setup;
    private org.telegram.messenger.purple.PurpleSyncChatSetupFlow.State setupState;
    private boolean limitShown;
    private Runnable rpcWatch;
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
        visible=false;if(markAction!=null){markAction.close();markAction=null;}if(setup!=null){setup.close();setup=null;}setupState=null;if(rpcWatch!=null){rpcWatch.run();rpcWatch=null;}if(control!=null){control.close();control=null;}super.onPause();
    }
    @Override public void onFragmentDestroy() {
        visible=false;if(markAction!=null){markAction.close();markAction=null;}if(setup!=null){setup.close();setup=null;}setupState=null;if(rpcWatch!=null){rpcWatch.run();rpcWatch=null;}if(control!=null){control.close();control=null;}super.onFragmentDestroy();
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
            if(frame.canFollowFill())items.add(UItem.asButton(FOLLOW_FILL,text(frame.status.resolution.action==PurpleSyncChatCore.Action.Follow?"Follow":"Fill")));
            if(frame.locallyPaused())items.add(UItem.asButton(LOCAL_RESUME,text("LocalResume")));
            if(frame.status.reason.equals("ReaderConsent")&&frame.current!=null&&frame.current.allowed)
                items.add(UItem.asButton(CONSENT,text("ReviewReaders")));
            if(frame.canSetup&&(setupState==null||!setupState.busy&&!setupState.resumable)) {
                items.add(UItem.asButton(CREATE,text("Create")));
                items.add(UItem.asButton(SELF,text("Saved")));
                items.add(UItem.asButton(EXISTING,text("Choose")));
            } else if(frame.canChange())items.add(UItem.asButton(CHANGE,text("Change")));
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
        items.add(UItem.asButton(REFRESH,text("Refresh")));
        items.add(UItem.asShadow(text("FirstPostHeld")));
    }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        if(!visible||control==null||!control.current())return;
        if(org.telegram.messenger.purple.PurpleSyncChatMarkActions.ACTIVATED&&frame!=null&&item.id>=REMOVE_MARK&&item.id<REMOVE_MARK+frame.staleMarks.size()){removeMark(frame.staleMarks.get(item.id-REMOVE_MARK));return;}
        if(item.id==REVIEW_SETTINGS&&setupState!=null&&setupState.phase.equals("Complete")){presentFragment(new PurpleSyncActivity(currentAccount));return;}
        if(item.id==FOLLOW_FILL&&frame!=null&&frame.canFollowFill()){followFill();return;}
        if(item.id==LOCAL_RESUME&&frame!=null&&frame.locallyPaused()){control.pauseHere(false,saved->{if(visible){if(!saved)message(text("Changed"));reload();}});return;}
        if(item.id==CHANGE&&frame!=null&&frame.canChange()){changeChoices();return;}
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
        PurpleSyncChatScreen.Control opening=control;if(opening==null||!visible)return;
        opening.offerFollowFill("config",offer->{
            if(!visible||control!=opening||!opening.current()||getParentActivity()==null)return;
            if(offer==null){message(text("Changed"));reload();return;}
            Runnable accept=()->opening.acceptFollowFill(offer,true,selection->{
                if(!visible||control!=opening||!opening.current())return;
                if(selection==null){message(text("Changed"));reload();return;}
                PurpleSyncChat.followFill(currentAccount,selection,()->visible&&identity!=null&&identity.current(),state->{
                    if(visible&&control==opening&&opening.current())message(text(state.committed?"FollowComplete":"FollowHeld"));
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
        PurpleSyncChatScreen.Control opening=control;
        AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("Change"));
        dialog.setItems(new CharSequence[]{text("Create"),text("Saved"),text("Choose")},(ignored,which)->{
            if(!visible||control!=opening||!opening.current())return;
            if(which==0)inspectChange(null,true);else if(which==1)inspectChange(PurpleSyncChatCore.Peer.self(),false);
            else {changePicker=true;choose();}
        });
        dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
    }
    private void inspectChange(PurpleSyncChatCore.Peer target,boolean create) {
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
            AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("Change"));
            dialog.setMessage(PurpleSyncChatScreen.changeDisclosure(offer,PurpleSyncChatActivity::text));dialog.setView(layout);
            dialog.setPositiveButton(text("ChangeMove"),(ignored,which)->opening.acceptChange(offer,true,true,cleanup.isChecked(),selection->{
                if(!visible||control!=opening||!opening.current())return;
                if(selection==null){message(text("Changed"));reload();return;}
                PurpleSyncChat.change(currentAccount,selection,()->visible&&identity!=null&&identity.current(),state->{
                    if(visible&&control==opening&&opening.current())message(text(state.committed?"ChangeComplete":"ChangeHeld"));
                });
            }));
            dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
        });
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
