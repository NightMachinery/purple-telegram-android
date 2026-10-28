package android.view;public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_ENTER=66,KEYCODE_NUMPAD_ENTER=160;
 private final int action,code,repeat;public KeyEvent(int a,int c,int r){action=a;code=c;repeat=r;}
 public int getAction(){return action;}public int getKeyCode(){return code;}public int getRepeatCount(){return repeat;}
}
