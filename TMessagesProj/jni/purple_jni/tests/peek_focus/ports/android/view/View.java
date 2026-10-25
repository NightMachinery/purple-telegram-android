package android.view;
public class View {
 public static final int VISIBLE=0,GONE=8,ACCESSIBILITY_LIVE_REGION_POLITE=1;public boolean attached=true;public int visibility;
 public interface OnClickListener{void onClick(View v);}public void setOnClickListener(OnClickListener l){}
 public boolean isAttachedToWindow(){return attached;}public void setVisibility(int v){visibility=v;}
}
