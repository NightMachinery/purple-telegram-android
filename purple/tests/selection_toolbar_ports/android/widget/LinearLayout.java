package android.widget;import android.view.View;import java.util.*;public class LinearLayout extends View{
 public static class LayoutParams{public int width,height,leftMargin,rightMargin;public LayoutParams(int w,int h){width=w;height=h;}}
 private final List<View> children=new ArrayList<>();public void addView(View v){children.add(v);}public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}public Object getContext(){return null;}
}
