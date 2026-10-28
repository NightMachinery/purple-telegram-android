package android.app;
public class Dialog implements android.content.DialogInterface {
 public static long focusDelay;public static int shown,dismissed;public static boolean throwDismiss;private final java.util.List<Runnable> focusEvents=new java.util.ArrayList<>();private android.content.DialogInterface.OnDismissListener listener;public boolean visible;public static Dialog latest;public android.view.View content;public Dialog(android.content.Context a){latest=this;}
 public boolean requestWindowFeature(int f){return true;} public void setCanceledOnTouchOutside(boolean b){}public void setContentView(android.view.View v){content=v;}
 public void setOnDismissListener(android.content.DialogInterface.OnDismissListener l){listener=l;}
 public android.view.Window getWindow(){return new android.view.Window();}
 public boolean dispatchKeyEvent(android.view.KeyEvent e){return false;}public void onWindowFocusChanged(boolean f){}
 private void focus(boolean focused){Runnable event=new Runnable(){public void run(){focusEvents.remove(this);onWindowFocusChanged(focused);}};focusEvents.add(event);org.telegram.messenger.AndroidUtilities.runOnUIThread(event,focusDelay);}
 public void hide(){visible=false;focus(false);}public void show(){boolean changed=!visible;visible=true;++shown;if(changed)focus(true);}public void dismiss(){for(Runnable event:focusEvents)org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(event);focusEvents.clear();visible=false;++dismissed;if(throwDismiss){throwDismiss=false;throw new IllegalStateException("dismiss fixture");}if(listener!=null)listener.onDismiss(this);}
}
