package android.app;
public class Dialog implements android.content.DialogInterface {
 public static int shown,dismissed;private android.content.DialogInterface.OnDismissListener listener;private boolean visible;public Dialog(android.content.Context a){}
 public boolean requestWindowFeature(int f){return true;} public void setCanceledOnTouchOutside(boolean b){}public void setContentView(android.view.View v){}
 public void setOnDismissListener(android.content.DialogInterface.OnDismissListener l){listener=l;}
 public android.view.Window getWindow(){return new android.view.Window();}
 public void show(){visible=true;++shown;}public void dismiss(){if(visible){visible=false;++dismissed;if(listener!=null)listener.onDismiss(this);}}
}
