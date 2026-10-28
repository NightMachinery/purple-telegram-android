package android.view;
public class View {
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8,ACCESSIBILITY_LIVE_REGION_POLITE=1;public boolean attached=true;public int visibility;
 public interface OnClickListener{void onClick(View v);}private OnClickListener listener;public void setOnClickListener(OnClickListener l){listener=l;}public boolean enabled=true;public void setEnabled(boolean e){enabled=e;}public boolean isEnabled(){return enabled;}public boolean performClick(){if(enabled&&listener!=null){listener.onClick(this);return true;}return false;}
 public boolean isAttachedToWindow(){return attached;}public void setVisibility(int v){visibility=v;}
 public int getVisibility(){return visibility;}public int top,width,height;
 public int getTop(){return top;}public int getMeasuredWidth(){return width;}public int getMeasuredHeight(){return height;}
 public void measure(int w,int h){onMeasure(w,h);}protected void onMeasure(int w,int h){width=MeasureSpec.getSize(w);height=MeasureSpec.getSize(h);}
 public void layout(int l,int t,int r,int b){top=t;onLayout(true,l,t,r,b);}protected void onLayout(boolean c,int l,int t,int r,int b){}
 public static class MeasureSpec {public static final int EXACTLY=1073741824,UNSPECIFIED=0;public static int makeMeasureSpec(int s,int m){return s|m;}public static int getSize(int s){return s&1073741823;}}
}
