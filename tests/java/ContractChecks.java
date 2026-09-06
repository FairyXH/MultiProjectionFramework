import com.template.lsposed.FeatureRegistry;
import com.template.lsposed.ModuleEntry;
import io.github.libxposed.api.XposedModule;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Map;

/** Each case runs in a fresh JVM so process-scoped state matches a fresh app process. */
public class ContractChecks {
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void parse(String text) throws Exception {
        Method method = FeatureRegistry.class.getDeclaredMethod("parseAndApply", String.class);
        method.setAccessible(true);
        method.invoke(null, text);
    }

    private static File storage() throws Exception {
        File file = Files.createTempDirectory("feature-state").resolve("state.json").toFile();
        Field field = FeatureRegistry.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(null, file);
        file.getParentFile().deleteOnExit();
        file.deleteOnExit();
        return file;
    }

    private static void load() throws Exception {
        Method method = FeatureRegistry.class.getDeclaredMethod("load");
        method.setAccessible(true);
        method.invoke(null);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String test = args[0];
        if (test.startsWith("hook")) {
            XposedModule.failFirst = test.equals("hook_retry");
            ModuleEntry module = new ModuleEntry();
            module.onModuleLoaded(() -> "com.example.target");
            module.onPackageLoaded(() -> "com.example.target");
            module.onPackageReady(() -> "com.example.target");
            System.out.println("attempts=" + XposedModule.attempts + " installed=" + XposedModule.installed);
            for (String method : new String[]{"attach"}) {
                check(XposedModule.installed.getOrDefault(method, 0) == 1,
                      "Each hook must register once; a failed registration must retry at package-ready");
            }
            check(!XposedModule.installed.containsKey("onResume"),
                  "The module must not install the Activity smoke-test hook");
            return;
        }
        FeatureRegistry.initialize(null);
        switch (test) {
            case "nan_write":
                FeatureRegistry.setFloat("multiplier", 2f);
                FeatureRegistry.setFloat("multiplier", Float.NaN);
                check(FeatureRegistry.getFloat("multiplier") == 2f, "NaN must not replace a finite setting");
                break;
            case "infinite_write":
                FeatureRegistry.setFloat("multiplier", 2f);
                FeatureRegistry.setFloat("multiplier", Float.POSITIVE_INFINITY);
                check(FeatureRegistry.getFloat("multiplier") == 2f, "Infinity must not replace a finite setting");
                break;
            case "malformed_bool":
                parse("{\"enabled\":garbage}");
                check(FeatureRegistry.getBool("enabled"), "Malformed booleans must not disable the module");
                break;
            case "nan_load":
                parse("{\"multiplier\":NaN}");
                check(FeatureRegistry.getFloat("multiplier") == 1f, "Non-finite persisted values must be ignored");
                break;
            case "precision_roundtrip":
                storage();
                float original = 1.2345678f;
                FeatureRegistry.setFloat("multiplier", original);
                Field field = FeatureRegistry.class.getDeclaredField("FLOATS");
                field.setAccessible(true);
                ((Map<String, Float>) field.get(null)).put("multiplier", 1f);
                load();
                float restored = FeatureRegistry.getFloat("multiplier");
                System.out.println("saved=" + original + " restored=" + restored);
                check(restored == original, "Persistence must preserve the float value exactly");
                break;
            case "bounded_load":
                File file = storage();
                Files.writeString(file.toPath(), "{\"multiplier\":3,\"padding\":" + " ".repeat(70_000) + "0}");
                load();
                check(FeatureRegistry.getFloat("multiplier") == 1f, "Ignore oversized state files before applying values");
                break;
            case "finite_clamp":
                FeatureRegistry.setFloat("multiplier", -2f);
                check(FeatureRegistry.getFloat("multiplier") == 1f, "Lower bound");
                FeatureRegistry.setFloat("multiplier", 300f);
                check(FeatureRegistry.getFloat("multiplier") == 30f, "Upper bound");
                parse("{\"enabled\":false,\"multiplier\":2.5}");
                check(!FeatureRegistry.getBool("enabled") && FeatureRegistry.getFloat("multiplier") == 2.5f,
                      "Valid existing state stays compatible");
                break;
            default: throw new IllegalArgumentException(test);
        }
        System.out.println(test + ": passed");
    }
}
