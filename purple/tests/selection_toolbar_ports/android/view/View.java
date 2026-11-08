package android.view;import java.util.*;public class View{
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8;private int visibility=VISIBLE;private boolean enabled=true;private Object tag;private final Map<Integer,Object> tags=new HashMap<>();private Object params;private CharSequence description;private OnClickListener click;
 public int left,top,right,bottom;public int getLeft(){return left;}public int getTop(){return top;}public int getRight(){return right;}public int getBottom(){return bottom;}public void layout(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 public interface OnClickListener{void click(View v);}public void setOnClickListener(OnClickListener l){click=l;}public boolean performClick(){if(click==null)return false;click.click(this);return true;}
 public int getVisibility(){return visibility;}public void setVisibility(int v){visibility=v;}public boolean isEnabled(){return enabled;}public void setEnabled(boolean b){enabled=b;}
 public Object getTag(){return tag;}public void setTag(Object t){tag=t;}public Object getTag(int k){return tags.get(k);}public void setTag(int k,Object v){tags.put(k,v);}
 public Object getLayoutParams(){return params;}public void setLayoutParams(Object p){params=p;}public CharSequence getContentDescription(){return description;}public void setContentDescription(CharSequence s){description=s;}public void requestLayout(){}
}
