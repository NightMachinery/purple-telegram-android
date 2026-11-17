from pathlib import Path
import hashlib
import json

R = Path(__file__).parent
P = R
B = R
blocks = {}

def method(file, declaration, after=0):
    text = file.read_text()
    start = text.index(declaration, after)
    brace = text.index('{', start)
    level, end = 1, brace + 1
    while level:
        if text[end] == '{': level += 1
        elif text[end] == '}': level -= 1
        end += 1
    result = text[start:end]
    blocks[str(file) + '#' + declaration] = hashlib.sha256(result.encode()).hexdigest()
    return result

def put(name, content):
    p = R / 'src' / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)

def prepare(repo, output):
    global R, P, B
    R = Path(output)
    P = Path(repo) / 'TMessagesProj/src/main/java/org/telegram'
    B = P
    if (R / 'src').exists():
        raise ValueError('fresh fixture sources required')
    for name in ['messenger/purple/PurpleFolderProjection.java', 'messenger/purple/PurpleFolderSelection.java',
                 'ui/PurpleFolderNavigation.java']:
        put('org/telegram/' + name, (P / name).read_text())

    put('org/telegram/messenger/AndroidUtilities.java', '''package org.telegram.messenger;
    public class AndroidUtilities {
     public static final java.util.ArrayList<Runnable> queue = new java.util.ArrayList<>();
     public static void runOnUIThread(Runnable r) { queue.add(r); }
     public static void runOnUIThread(Runnable r, int delay) { queue.add(r); }
     public static void cancelRunOnUIThread(Runnable r) { queue.removeIf(v -> v == r); }
     public static void drain() { int n=queue.size(); while(n-- > 0) queue.remove(0).run(); }
    }''')
    put('org/telegram/messenger/UserConfig.java', 'package org.telegram.messenger; public class UserConfig { public static final int MAX_ACCOUNT_COUNT=2; }')
    put('org/telegram/messenger/purple/PurpleCore.java', 'package org.telegram.messenger.purple; public class PurpleCore { public static class Loaded { } }')
    put('org/telegram/messenger/purple/PurpleWorkMode.java', '''package org.telegram.messenger.purple;
    public class PurpleWorkMode { public static class View {} public static View value=new View();
     public static View view(int account) {return value;} }''')
    put('org/telegram/messenger/purple/PurpleAccountOwner.java', '''package org.telegram.messenger.purple;
    public class PurpleAccountOwner {public static long role; public static long observedRoleEpoch(){return role;}}''')
    put('org/telegram/messenger/purple/PurpleAccountFiles.java', '''package org.telegram.messenger.purple;
    public class PurpleAccountFiles {public static String key="inert"; public static long lifetime=1;
     public static class Identity {public final String key; public final long lifetime; public final int slot;
     Identity(int slot){this.slot=slot;key=PurpleAccountFiles.key;lifetime=PurpleAccountFiles.lifetime;}
     public boolean current(){return key.equals(PurpleAccountFiles.key)&&lifetime==PurpleAccountFiles.lifetime;}}
     public static Identity capture(int account){return new Identity(account);} }''')
    put('org/telegram/messenger/purple/PurpleStripWatch.java', '''package org.telegram.messenger.purple;
    public class PurpleStripWatch {public static String definition="same";
    public static String stripIdentity(int account,PurpleCore.Loaded state){return definition;}}''')
    put('org/telegram/messenger/purple/PurpleGate.java', '''package org.telegram.messenger.purple;
    import java.util.ArrayList; import org.telegram.messenger.MessagesController;
    public class PurpleGate {public static PurpleCore.Loaded loaded=new PurpleCore.Loaded(); public static int revision;
    public static PurpleCore.Loaded state(){return loaded;} public static int generation(){return revision;}
    public static boolean isExtraView(MessagesController.DialogFilter f){return f.extra;}
    public static ArrayList<MessagesController.DialogFilter> shownFiltersQuiet(int account,ArrayList<MessagesController.DialogFilter> raw){return raw;}
    }''')
    native_select = method(B / 'messenger/MessagesController.java', 'public void selectDialogFilter(DialogFilter filter, int index)')
    put('org/telegram/messenger/MessagesController.java', '''package org.telegram.messenger;
    import java.util.ArrayList;
    public class MessagesController {
     public static final MessagesController INSTANCE=new MessagesController();
     public static MessagesController getInstance(int account){return INSTANCE;}
     public static class DialogFilter {
     public int id,localId; public boolean locked,extra,title_noanimate; public String name="folder"; public Object entities;
     public final ArrayList<Object> dialogs=new ArrayList<>(), dialogsForward=new ArrayList<>();
     public DialogFilter(int id){this.id=id;localId=id;} public boolean isDefault(){return id==0;}}
     public ArrayList<DialogFilter> filters=new ArrayList<>(); public Runnable onSelect;
     public final DialogFilter[] selectedDialogFilter=new DialogFilter[2];
     public ArrayList<DialogFilter> getDialogFiltersUnrestricted(){return filters;}
     public ArrayList<DialogFilter> getDialogFilters(){return filters;}
     private void sortDialogs(Object value){if(onSelect!=null){Runnable r=onSelect;onSelect=null;r.run();}}
    ''' + native_select + '\n}')

    tabs = P / 'ui/Components/FilterTabsView.java'
    scroll = method(tabs, 'public void scrollToTab(Tab tab, int position)')
    stop = method(tabs, 'public void stopAnimatingIndicator()')
    put('org/telegram/ui/Components/FilterTabsView.java', '''package org.telegram.ui.Components;
    import java.util.ArrayList; import org.telegram.messenger.AndroidUtilities;
    public class FilterTabsView {
     public enum PagePreparation {LEGACY, READY, REFUSED}
     public interface Delegate {PagePreparation preparePageSelection(Tab t,boolean f);void onPageSelected(Tab t,boolean f);}
     public static class Tab {public int id;public boolean isLocked,isDefault;Tab(int id){this.id=id;}}
     public final ArrayList<Tab> tabs=new ArrayList<>(); public Delegate delegate;
     public int currentPosition,scrollingToChild,previousPosition,previousId,selectedTabId=-1,manualScrollingToPosition,manualScrollingToId,visibility;
     public float animationTime,animatingIndicatorProgress; public boolean animatingIndicator,enabled=true; private long transitionSerial;
     public final Runnable animationRunnable=()->{};
     public void setEnabled(boolean v){enabled=v;} public void scrollToChild(int p){}
     public void shakeLock(int id){} public int getVisibility(){return visibility;}
     public boolean isLocked(int id){return tabs.get(id).isLocked;}
     public int getCurrentTabId(){return selectedTabId;} public int getFirstTabId(){return 0;}
     public int getNextPageId(boolean forward){int n=currentPosition+(forward?1:-1);return n>=0&&n<tabs.size()?n:-1;}
     public void setIsEditing(boolean v){} public void finishAddingTabs(boolean v){}
     public void removeTabs(){stopAnimatingIndicator();tabs.clear();}
     public void resetTabId(){selectedTabId=-1;}
     public Tab getTab(int i){return i<0||i>=tabs.size()?null:tabs.get(i);}
     public void addTab(int id,int stableId,String name,Object e,boolean noanimate,boolean def,boolean locked){Tab t=new Tab(id);t.isLocked=locked;t.isDefault=def;tabs.add(t);}
     public void selectTabWithId(int id,float progress){if(progress==1){currentPosition=id;selectedTabId=id;}}
     public void selectFirstTab(){scrollToTab(tabs.get(0),0);}
    ''' + scroll + '\n' + stop + '\n}')

    dialogs = P / 'ui/DialogsActivity.java'
    declarations = [
     'private FilterTabsView.PagePreparation purplePrepareFolder(FilterTabsView.Tab tab, Runnable bind)',
     'private void purpleRequestFolderRefresh()', 'private void purpleRejectFolder(PurpleFolderNavigation.Attempt attempt)',
     'private boolean purpleFolderProgress(PurpleFolderNavigation.Attempt attempt)', 'private boolean purpleContinueFolderSwipe()',
     'private void purpleCancelFolderMotion()', 'private void purpleCancelFolderMotion(boolean indicator)', 'private boolean purpleRebindFolders(PurpleFolderNavigation.Rebuild rebuild)',
     'public void switchToCurrentSelectedMode(boolean animated)', 'private void updateFilterTabs(boolean force, boolean animated)',
     'public void onPageSelected(FilterTabsView.Tab tab, boolean forward)', 'public void onPageScrolled(float progress)']
    body = '\n'.join(method(dialogs,d) for d in declarations)
    swipe = method(dialogs, 'private boolean prepareForMoving(MotionEvent ev, boolean forward)')
    text=dialogs.read_text();offset=text.index('final PurpleFolderNavigation.Attempt folderAttempt =')
    listener=method(dialogs,'public void onAnimationEnd(Animator animator)',offset)
    put('org/telegram/ui/DialogsActivity.java', '''package org.telegram.ui;
    import java.util.ArrayList; import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
    import org.telegram.ui.Components.FilterTabsView;
    public class DialogsActivity implements FilterTabsView.Delegate {
     static final int DIALOGS_TYPE_DEFAULT=0,ARCHIVE_ITEM_STATE_HIDDEN=1;
     static class View {static final int VISIBLE=0,GONE=8;}
     static class RecyclerView {static final int TOUCH_SLOP_PAGING=1,TOUCH_SLOP_DEFAULT=0;}
     static class R {static class string {static final int FilterAllChats=1;}}
     static class LocaleController {static String getString(int id){return "all";}}
     static class LimitReachedBottomSheet {static final int TYPE_FOLDERS=1;LimitReachedBottomSheet(Object...v){}}
     static class Adapter {int type;Runnable onResume;void setDialogsType(int type){this.type=type;}void resume(){if(onResume!=null){Runnable r=onResume;onResume=null;r.run();}}void pause(){}}
     static class ListView {Runnable onStop;void stopScroll(){if(onStop!=null){Runnable r=onStop;onStop=null;r.run();}}void updatePullState(){}void setScrollEnabled(boolean v){}void setScrollingTouchSlop(int v){}void requestLayout(){}}
     static class LayoutManager {int resets;void scrollToPositionWithOffset(int p,int o){resets++;}}
     static class ViewPage {int selectedType,dialogsType,visibility=View.GONE,archivePullViewState;boolean isLocked;
     final ListView listView=new ListView();final Adapter dialogsAdapter=new Adapter();final LayoutManager layoutManager=new LayoutManager();
     void setVisibility(int v){visibility=v;}int getVisibility(){return visibility;}int getMeasuredWidth(){return 100;}
     void setTranslationX(float v){}void requestLayout(){}}
     static class ActionBar {void setEnabled(boolean v){}}
     static class Option {void dismiss(){}}
     static class Container {boolean hasFragment(){return false;}}
     static class PurplePort {boolean changed(int account){return false;}boolean skipPageScroll(int a,int v,boolean searching){return v!=View.VISIBLE&&!searching;}}
     static class Animator {boolean removed,cancelled;void removeAllListeners(){removed=true;}void cancel(){cancelled=true;}}
     static class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}
     static class MotionEvent {float getX(){return 0;}}
     final ViewPage[] initialPages={new ViewPage(),new ViewPage()}; ViewPage[] viewPages=initialPages;
     final FilterTabsView filterTabsView=new FilterTabsView(); final PurpleFolderNavigation purpleFolders=new PurpleFolderNavigation();
     PurpleFolderNavigation.Binding purpleFolderBinding; ActionBar actionBar=new ActionBar(); Object fragmentView=new Object(); final PurplePort purple=new PurplePort();
     int currentAccount,initialDialogsType;float scrollYOffset,additionalOffset;boolean searching,animatingForward,startedTracking,maybeStartTracking,tabsAnimationInProgress;
     boolean inPreviewMode,searchIsShowed,canShowFilterTabsView;Object context;Container rightSlidingDialogContainer;Option filterOptions;Animator tabsAnimation;
     Runnable onCounters;
     public void refresh(){updateFilterTabs(true,false);}
     public boolean startSwipe(boolean f){return new ContentView().prepareForMoving(new MotionEvent(),f);}
     public boolean continueSwipe(){return purpleContinueFolderSwipe();}
     public DialogsActivity(){filterTabsView.delegate=this;viewPages[0].visibility=View.VISIBLE;updateFilterTabs(true,false);}
     public FilterTabsView.PagePreparation preparePageSelection(FilterTabsView.Tab t,boolean f){return purplePrepareFolder(t,()->onPageSelected(t,f));}
     MessagesController getMessagesController(){return MessagesController.INSTANCE;}
     void showDialog(Object v){}boolean hasHiddenArchive(){return false;}void showDoneItem(boolean v){}
     void showScrollbars(boolean v){}void checkListLoad(ViewPage p){}
     void updateFilterTabsVisibility(boolean a){filterTabsView.visibility=canShowFilterTabsView?View.VISIBLE:View.GONE;}
     void updateCounters(boolean v){if(onCounters!=null){Runnable r=onCounters;onCounters=null;r.run();}}
     class ContentView {int startedTrackingX;ContentView getParent(){return this;}void requestDisallowInterceptTouchEvent(boolean v){}
    ''' + swipe + '''
     }
     public AnimatorListenerAdapter swipeListener(boolean folderBack){final PurpleFolderNavigation.Attempt folderAttempt=purpleFolders.pending();
     return new AnimatorListenerAdapter(){''' + listener + '''};}
    ''' + body + '\n}')

    manifest={str(P / name): hashlib.sha256((P / name).read_bytes()).hexdigest() for name in ['messenger/purple/PurpleFolderSelection.java', 'messenger/purple/PurpleFolderProjection.java', 'ui/PurpleFolderNavigation.java', 'ui/Components/FilterTabsView.java', 'ui/DialogsActivity.java', 'messenger/MessagesController.java']}
    (R/'input-hashes.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (R/'source-extraction.json').write_text(json.dumps({'exactMethodBodies':blocks,'fullSources':['PurpleFolderSelection','PurpleFolderProjection','PurpleFolderNavigation'],
     'ports':'inert UI widgets, handler queue, account/preset publisher and native controller sort callback; no Android runtime',
     'originalReproduction':'Baseline mismatch witnesses are recorded separately; this suite checks the current candidate'},indent=2)+'\n')
    print(json.dumps({'prepared':True,'methodBodies':len(blocks),'sourceFiles':len(list((R/'src').rglob('*.java')))}))
