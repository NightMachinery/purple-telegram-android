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
import org.telegram.messenger.purple.PurpleSyncMagicScreen;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The Sync chat screen (Magic Purple Sync): which chat this device syncs through, a problem shown once, and
 * the user's choices: check again, open the chat, show it in the chat list, use another chat or the magic chat
 * again, and join the other devices when the chat holds another sync group's settings.
 */
public final class PurpleSyncChatActivity extends UniversalFragment {
    private static final int CHECK=0x70750110, OPEN=CHECK+1, LISTED=CHECK+2, CHOOSE=CHECK+3, USE_DEFAULT=CHECK+4, JOIN=CHECK+5;
    private PurpleSyncMagicScreen.Frame frame;
    private org.telegram.messenger.purple.PurpleAccountFiles.Identity identity;
    private volatile boolean visible;
    private boolean busy;
    private org.telegram.messenger.purple.PurpleSyncMagicCore.Problem shownProblem;
    private Runnable rpcWatch;

    public PurpleSyncChatActivity(int account) { setCurrentAccount(account); }
    @Override public boolean onFragmentCreate() {
        identity=org.telegram.messenger.purple.PurpleAccountFiles.capture(currentAccount);
        return identity!=null&&identity.current()&&super.onFragmentCreate();
    }
    static String text(String key,Object... args) {
        String value=LocaleController.getString("PurpleSyncChat"+key);
        return args.length==0?value:String.format(Locale.getDefault(),value,args);
    }
    /** What a chat's readers can see, for the review dialogs; goes with the old setup screen in A7. */
    static String disclosure(org.telegram.messenger.purple.PurpleSyncChatScreen.Candidate candidate) {
        return org.telegram.messenger.purple.PurpleSyncChatScreen.disclosure(candidate,PurpleSyncChatActivity::text);
    }
    private boolean live() { return visible&&identity!=null&&identity.current(); }
    @Override protected CharSequence getTitle() { return text("Title"); }
    @Override public View createView(Context context) { return super.createView(context); }
    @Override public void onResume() {
        super.onResume();visible=true;
        if(rpcWatch!=null)rpcWatch.run();
        rpcWatch=org.telegram.messenger.purple.PurpleRpcRequests.observe(currentAccount,this::update);
        if(identity==null||!identity.current()){finishFragment();return;}
        show(PurpleSyncChat.magicFrame(currentAccount));
        PurpleSyncChat.ensureResolved(currentAccount,false,status->{if(live())show(PurpleSyncChat.magicFrame(currentAccount));});
    }
    @Override public void onPause() { visible=false;if(rpcWatch!=null){rpcWatch.run();rpcWatch=null;}super.onPause(); }
    @Override public void onFragmentDestroy() { visible=false;if(rpcWatch!=null){rpcWatch.run();rpcWatch=null;}super.onFragmentDestroy(); }
    private void update() { if(visible&&listView!=null)listView.adapter.update(true); }
    /** Shows {@code value}; a problem not shown before gets one dialog, then it is reported shown. */
    private void show(PurpleSyncMagicScreen.Frame value) {
        frame=value;busy=false;update();
        if(value.line==PurpleSyncMagicScreen.Line.Problem&&value.showProblem&&value.problem!=shownProblem&&live()&&getParentActivity()!=null){
            shownProblem=value.problem;
            PurpleSyncChat.problemShown(currentAccount);
            message(text("MagicPaused",problem(value)));
        }
    }
    @Override protected void fillItems(ArrayList<UItem> items,UniversalAdapter adapter) {
        items.add(UItem.asHeader(text("Title")));
        org.telegram.messenger.purple.PurpleRpcRetry.Transient rpcWait=org.telegram.messenger.purple.PurpleRpcRequests.waiting(currentAccount);
        if(rpcWait!=null)items.add(UItem.asShadow(org.telegram.messenger.purple.PurpleRpcRequests.text(rpcWait)));
        PurpleSyncMagicScreen.Frame shown=frame;
        if(shown==null){items.add(UItem.asShadow(text("MagicLooking")));return;}
        items.add(UItem.asShadow(line(shown)));
        if(shown.manual&&shown.line==PurpleSyncMagicScreen.Line.Using)items.add(UItem.asShadow(text("MagicPicked")));
        items.add(UItem.asButton(CHECK,text("MagicCheck")));
        if(shown.canOpen())items.add(UItem.asButton(OPEN,text("MagicOpen")));
        if(shown.canJoin()){
            items.add(UItem.asButton(JOIN,text("MagicJoin")));
            items.add(UItem.asShadow(text("MagicJoinAbout")));
        }
        if(shown.canList()){
            items.add(UItem.asCheck(LISTED,text("MagicListed")).setChecked(shown.listed));
            items.add(UItem.asShadow(text("MagicListedAbout")));
        }
        if(shown.canUseDefault())items.add(UItem.asButton(USE_DEFAULT,text("MagicUseDefault")));
        items.add(UItem.asButton(CHOOSE,text("MagicChoose")));
        items.add(UItem.asShadow(text("MagicChooseAbout")));
    }
    private String line(PurpleSyncMagicScreen.Frame shown) {
        switch(shown.line){
            case Using:return text("MagicUsing",title(shown.chat));
            case Waiting:return shown.retryAt>0?text("MagicWaiting",LocaleController.getInstance().getFormatterDay().format(shown.retryAt*1000)):text("MagicWaitingLater");
            case Problem:return text("MagicPaused",problem(shown));
            default:return text("MagicLooking");
        }
    }
    private String title(PurpleSyncChatCore.Peer chat) {
        if(chat==null)return org.telegram.messenger.purple.PurpleSyncMagicCore.TITLE;
        if(PurpleSyncChatCore.Peer.self().equals(chat))return text("Saved");
        TLRPC.Chat local=chat.kind==PurpleSyncChatCore.PeerKind.Channel?MessagesController.getInstance(currentAccount).getChat(chat.id):null;
        return local!=null&&local.title!=null&&!local.title.isEmpty()?local.title:org.telegram.messenger.purple.PurpleSyncMagicCore.TITLE;
    }
    private static String problem(PurpleSyncMagicScreen.Frame shown) { return text("Problem"+shown.problem.name()); }
    @Override protected void onClick(UItem item,View view,int position,float x,float y) {
        PurpleSyncMagicScreen.Frame shown=frame;
        if(!live()||shown==null||busy)return;
        if(item.id==CHECK){busy=true;PurpleSyncChat.magicCheck(currentAccount,this::live,this::show);}
        else if(item.id==OPEN&&shown.canOpen())open(shown.chat);
        else if(item.id==LISTED&&shown.canList()){
            boolean next=!shown.listed;busy=true;
            PurpleSyncChat.magicList(currentAccount,next,this::live,ok->{if(!ok)message(text("MagicNotSaved"));show(PurpleSyncChat.magicFrame(currentAccount));});
        }
        else if(item.id==USE_DEFAULT&&shown.canUseDefault()){busy=true;PurpleSyncChat.magicUseDefault(currentAccount,this::live,this::show);}
        else if(item.id==CHOOSE)choose();
        else if(item.id==JOIN&&shown.canJoin())confirmJoin();
    }
    @Override protected boolean onLongClick(UItem item,View view,int position,float x,float y) { return false; }
    private void open(PurpleSyncChatCore.Peer chat) {
        if(chat.kind!=PurpleSyncChatCore.PeerKind.Channel)return;
        Bundle args=new Bundle();args.putLong("chat_id",chat.id);
        presentFragment(new ChatActivity(args));
    }
    /** The picker over the local dialog list (no search); only Saved Messages or a supergroup the user is in alone is taken. */
    private void choose() {
        final long self=UserConfig.getInstance(currentAccount).getClientUserId();
        Bundle args=new Bundle();args.putBoolean("onlySelect",true);args.putInt("dialogsType",0);args.putBoolean("allowSwitchAccount",false);
        DialogsActivity picker=new DialogsActivity(args);picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment,dids,message,param,notify,schedule,repeat,topics)->{
            if(!live()||dids==null||dids.size()!=1||topics!=null)return false;
            long did=dids.get(0).dialogId;
            if(DialogObject.isEncryptedDialog(did)||DialogObject.isFolderDialogId(did)||dids.get(0).topicId!=0)return false;
            PurpleSyncChatCore.Peer chat;
            if(did==self)chat=PurpleSyncChatCore.Peer.self();
            else if(did>0)chat=PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.User,did);
            else {
                TLRPC.Chat local=MessagesController.getInstance(currentAccount).getChat(-did);
                if(local==null)return false;
                chat=PurpleSyncChatCore.Peer.of(org.telegram.messenger.ChatObject.isChannel(local)?PurpleSyncChatCore.PeerKind.Channel:PurpleSyncChatCore.PeerKind.Chat,-did);
            }
            fragment.finishFragment();
            PurpleSyncMagicScreen.Pick pick=PurpleSyncChat.magicPick(currentAccount,chat);
            if(pick!=PurpleSyncMagicScreen.Pick.Allowed){message(text("MagicPick"+pick.name()));return true;}
            busy=true;PurpleSyncChat.magicChoose(currentAccount,chat,this::live,this::show);
            return true;
        });
        presentFragment(picker);
    }
    private void confirmJoin() {
        if(getParentActivity()==null)return;
        AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(text("MagicJoin"));dialog.setMessage(text("MagicJoinAbout"));
        dialog.setPositiveButton(text("MagicJoin"),(ignored,which)->{
            if(!live())return;busy=true;
            PurpleSyncChat.magicJoin(currentAccount,this::live,ok->{
                show(PurpleSyncChat.magicFrame(currentAccount));
                if(!ok){message(text("MagicJoinFailed"));return;}
                presentFragment(new PurpleSyncActivity(currentAccount));
            });
        });
        dialog.setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel),null);showDialog(dialog.create());
    }
    private void message(String text) {
        if(!visible||getParentActivity()==null)return;
        new AlertDialog.Builder(getParentActivity()).setTitle(PurpleSyncChatActivity.text("Title")).setMessage(text)
                .setPositiveButton(LocaleController.getString(org.telegram.messenger.R.string.OK),null).show();
    }
}
