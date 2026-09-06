"""Host contract tests; Android builds separately validate the real API signatures."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

TESTS = Path(__file__).resolve().parent
ROOT = Path(os.environ.get("TEMPLATE_TEST_ROOT", TESTS.parent))


class JavaContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.classes = Path(cls.temp.name)
        sources = list((TESTS / "java").rglob("*.java"))
        overlay = (ROOT / "app/src/main/java/com/template/lsposed/ui/OverlayController.java").read_text()
        gesture_methods = overlay[overlay.index("    private void makeDraggable("):
                                  overlay.index("    private void hideOverlay(")]
        gesture = cls.classes / "GestureChecks.java"
        gesture.write_text('''import android.view.*;
import android.widget.FrameLayout;
public class GestureChecks {
 private int dp(float value){return (int)value;}
''' + gesture_methods + '''
 public static void main(String[] args){
  View view=new View();
  new GestureChecks().makeDraggable(view,view,new FrameLayout.LayoutParams(),true);
  view.listener.handle(view,new MotionEvent(MotionEvent.ACTION_DOWN,0));
  if(args[0].equals("drag")) view.listener.handle(view,new MotionEvent(MotionEvent.ACTION_MOVE,20));
  view.listener.handle(view,new MotionEvent(args[0].equals("cancel")?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,20));
  int expected=args[0].equals("tap")?1:0;
  if(view.clicks!=expected) throw new AssertionError("Expected "+expected+" clicks, got "+view.clicks);
 }
}
''')
        sources.append(gesture)
        main = ROOT / "app/src/main/java/com/template/lsposed"
        sources.extend(main / (name + ".java") for name in (
            "ModuleEntry", "FeatureRegistry", "FeatureState", "TemplateConfig"))
        result = subprocess.run(["javac", "--release", "17", "-d", str(cls.classes),
                                 *map(str, sources)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case):
        result = subprocess.run(["java", "-cp", str(self.classes), "ContractChecks", case],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_gestures(self):
        for case in ("cancel", "tap", "drag"):
            with self.subTest(gesture=case):
                result = subprocess.run(["java", "-cp", str(self.classes), "GestureChecks", case],
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


for case in ("hook_retry", "hook_once", "nan_write", "infinite_write",
             "malformed_bool", "nan_load", "precision_roundtrip", "bounded_load", "finite_clamp"):
    setattr(JavaContractTests, "test_" + case, lambda self, case=case: self.run_case(case))


if __name__ == "__main__":
    unittest.main()
