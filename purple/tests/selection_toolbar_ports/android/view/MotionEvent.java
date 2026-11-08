package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
    private final int action; private final float x, y;
    public MotionEvent(int action, float x, float y) { this.action=action; this.x=x; this.y=y; }
    public int getActionMasked() { return action; }
    public float getX() { return x; }
    public float getY() { return y; }
}
