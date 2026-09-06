package android.view; public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 private final int action;private final float x;
 public MotionEvent(int action,float x){this.action=action;this.x=x;}
 public int getActionMasked(){return action;}public float getRawX(){return x;}public float getRawY(){return 0;}
}
