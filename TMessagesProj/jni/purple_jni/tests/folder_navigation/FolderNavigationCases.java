package org.telegram.ui;

import java.util.ArrayList;
import java.util.Arrays;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.ui.Components.FilterTabsView;

public final class FolderNavigationCases {
    private static int checks;
    private static MessagesController.DialogFilter f(int id) { return new MessagesController.DialogFilter(id); }
    private static void require(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }
    private static void publish(String definition, MessagesController.DialogFilter... filters) {
        MessagesController.INSTANCE.filters = new ArrayList<>(Arrays.asList(filters));
        PurpleGate.loaded = new PurpleCore.Loaded();
        PurpleGate.revision++;
        PurpleWorkMode.value = new PurpleWorkMode.View();
        PurpleStripWatch.definition = definition;
        PurpleFolderProjection.publish(PurpleGate.loaded, PurpleGate.revision);
    }
    private static DialogsActivity scene(MessagesController.DialogFilter... filters) {
        AndroidUtilities.queue.clear();
        MessagesController.INSTANCE.onSelect = null;
        Arrays.fill(MessagesController.INSTANCE.selectedDialogFilter, null);
        publish("same", filters);
        return new DialogsActivity();
    }
    private static int displayed(DialogsActivity a) {
        int type = a.viewPages[0].dialogsType;
        if (type != 7 && type != 8) return 0;
        MessagesController.DialogFilter filter = MessagesController.INSTANCE.selectedDialogFilter[type - 7];
        return filter == null ? -1 : filter.id;
    }
    private static void click(DialogsActivity a, FilterTabsView.Tab tab, int position) {
        a.filterTabsView.scrollToTab(tab, position);
        a.onPageScrolled(1f);
    }
    private static void click(DialogsActivity a, int position) {
        click(a, a.filterTabsView.getTab(position), position);
    }
    private static void agrees(DialogsActivity a, int id, String why) {
        require(displayed(a) == id, why + " native bound filter");
        require(a.viewPages[0].selectedType == a.filterTabsView.getCurrentTabId(), why + " indicator position");
    }
    public static void main(String[] args) throws Exception {
        MessagesController.DialogFilter zero=f(0), ten=f(10), twenty=f(20);
        DialogsActivity normal=scene(zero,ten,twenty);
        require(normal.purpleFolders.samePage(0,normal.filterTabsView.getTab(0),normal.viewPages[0]),"actual default same-page shortcut");
        click(normal,2); agrees(normal,20,"unchanged first click");
        click(normal,1); agrees(normal,10,"second native slot");
        require(normal.viewPages[0] == normal.initialPages[0],"native page objects swap twice");

        DialogsActivity reordered=scene(zero,ten,twenty);
        FilterTabsView.Tab renderedTwenty=reordered.filterTabsView.getTab(2);
        MessagesController.INSTANCE.filters=new ArrayList<>(Arrays.asList(zero,twenty,ten));
        click(reordered,renderedTwenty,2); agrees(reordered,20,"reordered first click");
        require(reordered.viewPages[0].selectedType==1,"reordered logical folder remapped");

        DialogsActivity shrunk=scene(zero,ten,twenty);
        FilterTabsView.Tab oldTwenty=shrunk.filterTabsView.getTab(2);
        MessagesController.INSTANCE.filters=new ArrayList<>(Arrays.asList(zero,twenty));
        click(shrunk,oldTwenty,2); agrees(shrunk,20,"shrink retained target first click");
        DialogsActivity removed=scene(zero,ten,twenty);
        FilterTabsView.Tab missing=removed.filterTabsView.getTab(2);
        publish("same",zero,ten);
        click(removed,missing,2); agrees(removed,0,"removed target refuses highlight");
        require(!removed.filterTabsView.animatingIndicator,"refused indicator not queued");

        DialogsActivity cutover=scene(zero,ten,twenty);
        MessagesController.INSTANCE.onSelect=()->{
            publish("same",zero,twenty,ten);
            cutover.refresh();
        };
        click(cutover,2); agrees(cutover,20,"reentrant native bind cutover retains intent");
        require(!cutover.filterTabsView.animatingIndicator,"old post-bind continuation retired");

        MessagesController.DialogFilter view=f(10); view.extra=true;
        DialogsActivity changedDefinition=scene(zero,view,twenty);
        FilterTabsView.Tab oldView=changedDefinition.filterTabsView.getTab(1);
        MessagesController.DialogFilter reused=f(10); reused.extra=true;
        publish("different",zero,reused,twenty);
        click(changedDefinition,oldView,1); agrees(changedDefinition,0,"synthetic ordinal definition reuse refused");
        DialogsActivity reload=scene(zero,view,twenty);
        FilterTabsView.Tab sameView=reload.filterTabsView.getTab(1);
        MessagesController.DialogFilter replacement=f(10);replacement.extra=true;
        publish("same",zero,replacement,twenty);
        click(reload,sameView,1); agrees(reload,10,"same-definition reload rebinds current object");
        require(MessagesController.INSTANCE.selectedDialogFilter[reload.viewPages[0].dialogsType-7]==replacement,"no old native object substituted");

        DialogsActivity stable=scene(zero,ten,twenty);click(stable,1);
        int resets=stable.viewPages[0].layoutManager.resets;
        stable.refresh();agrees(stable,10,"unchanged forced refresh");
        require(stable.viewPages[0].layoutManager.resets==resets,"unchanged native object preserves scroll");

        DialogsActivity swipe=scene(zero,ten,twenty);
        require(swipe.startSwipe(true),"native gesture preparation");
        DialogsActivity.Animator animation=new DialogsActivity.Animator();swipe.tabsAnimation=animation;
        DialogsActivity.AnimatorListenerAdapter listener=swipe.swipeListener(false);
        listener.onAnimationEnd(animation);agrees(swipe,10,"native gesture completion");
        require(swipe.purpleFolders.pending()==null,"gesture ticket consumed");
        require(swipe.startSwipe(true),"second gesture preparation");
        DialogsActivity.Animator cancelled=new DialogsActivity.Animator();swipe.tabsAnimation=cancelled;
        DialogsActivity.AnimatorListenerAdapter cancelledListener=swipe.swipeListener(true);
        cancelledListener.onAnimationEnd(cancelled);agrees(swipe,10,"gesture back cancellation");
        require(swipe.purpleFolders.pending()==null,"back retires its ticket");
        require(swipe.startSwipe(true),"newer gesture admission");
        DialogsActivity.Animator newer=new DialogsActivity.Animator();swipe.tabsAnimation=newer;
        cancelledListener.onAnimationEnd(cancelled);
        require(swipe.tabsAnimation==newer,"old native listener cannot clear successor animator");
        swipe.swipeListener(false).onAnimationEnd(newer);agrees(swipe,20,"successor gesture survives late callback");

        DialogsActivity reversed=scene(zero,ten,twenty);click(reversed,1);
        require(reversed.startSwipe(true),"reversal incoming prepared");
        PurpleFolderNavigation.Attempt oldAttempt=reversed.purpleFolders.pending();
        reversed.purpleFolders.cancel(oldAttempt);
        DialogsActivity.ViewPage page=reversed.viewPages[0];reversed.viewPages[0]=reversed.viewPages[1];reversed.viewPages[1]=page;
        require(reversed.continueSwipe(),"reversed physical page resolves its stored logical binding");
        require(reversed.purpleFolders.pending()!=oldAttempt,"reversal owns a new ticket");
        require(reversed.purpleFolders.pending().position()==1,"reverse target is old actual page");
        reversed.onPageScrolled(1f);agrees(reversed,10,"reversal completion");

        DialogsActivity account=scene(zero,ten,twenty);
        FilterTabsView.Tab accountTab=account.filterTabsView.getTab(2);
        PurpleAccountFiles.lifetime++;
        click(account,accountTab,2);agrees(account,0,"account lifetime change refuses old rendered intent");
        DialogsActivity role=scene(zero,ten,twenty);
        FilterTabsView.Tab roleTab=role.filterTabsView.getTab(2);
        PurpleAccountOwner.role++;
        click(role,roleTab,2);agrees(role,0,"account role change refuses old rendered intent");
        DialogsActivity close=scene(zero,ten,twenty);
        require(close.startSwipe(true),"close has actual pending binding");
        close.purpleFolders.close();close.onPageScrolled(1f);
        agrees(close,0,"fragment retirement ignores late completion");
        DialogsActivity interrupted=scene(zero,ten,twenty);
        require(interrupted.startSwipe(true),"interrupted gesture prepared");
        DialogsActivity.Animator interruptedAnimation=new DialogsActivity.Animator();interrupted.tabsAnimation=interruptedAnimation;
        DialogsActivity.AnimatorListenerAdapter interruptedListener=interrupted.swipeListener(false);
        click(interrupted,2);agrees(interrupted,20,"tap supersedes native swipe");
        require(interruptedAnimation.cancelled && interruptedAnimation.removed,"native swipe writer cancelled before new binding");
        interruptedListener.onAnimationEnd(interruptedAnimation);agrees(interrupted,20,"cancelled swipe listener stays inert");
        DialogsActivity resumeReentry=scene(zero,ten,twenty);
        resumeReentry.viewPages[0].dialogsAdapter.onResume=()->click(resumeReentry,1);
        click(resumeReentry,2);agrees(resumeReentry,10,"nested resume intent supersedes old preparation");
        DialogsActivity stopReentry=scene(zero,ten,twenty);
        stopReentry.viewPages[0].listView.onStop=()->click(stopReentry,1);
        click(stopReentry,2);agrees(stopReentry,10,"nested stopScroll intent cannot inherit outer binding");
        DialogsActivity detached = scene(zero, ten, twenty);
        FilterTabsView.Tab previous = detached.filterTabsView.getTab(2);
        detached.fragmentView = null;
        detached.actionBar = null;
        publish("same", zero, twenty, ten);
        AndroidUtilities.runOnUIThread(detached::refresh);
        AndroidUtilities.drain();
        require(detached.filterTabsView.getTab(2) == previous, "detached folder refresh leaves the old view alone");
        detached.fragmentView = new Object();
        detached.actionBar = new DialogsActivity.ActionBar();
        detached.refresh();
        click(detached, 2);
        agrees(detached, 10, "reattachment refresh uses the latest folder order");

        DialogsActivity detachDuringRefresh = scene(zero, ten, twenty);
        detachDuringRefresh.viewPages[0].dialogsAdapter.onResume = () -> {
            detachDuringRefresh.actionBar = null;
        };
        detachDuringRefresh.refresh();
        agrees(detachDuringRefresh, 0, "animation cleanup remains safe when adapter resume clears the action bar");

        System.out.println("FOLDER_NAVIGATION_CHECKS="+checks);
        System.out.println("NO_ANDROID_RUNTIME_CLAIM");
    }
}
