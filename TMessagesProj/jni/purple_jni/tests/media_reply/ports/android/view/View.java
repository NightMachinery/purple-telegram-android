package android.view;
public class View {public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View view);void onViewDetachedFromWindow(View view);}public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener){} }
