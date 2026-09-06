package android.view; public class View {
 public interface Listener {boolean handle(View v,MotionEvent e);}
 public Listener listener;public int clicks;public View parent;
 public void setOnTouchListener(Listener l){listener=l;}
 public void setLayoutParams(Object p){}
 public int getLeft(){return 0;}public int getTop(){return 0;}
 public int getWidth(){return 100;}public int getHeight(){return 100;}
 public View getParent(){return parent;}
 public boolean performClick(){clicks++;return true;}
}
