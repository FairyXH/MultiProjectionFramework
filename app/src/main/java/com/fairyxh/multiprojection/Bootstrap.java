package com.fairyxh.multiprojection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fairyxh.multiprojection.ui.OverlayController;

import io.github.libxposed.api.XposedInterface;

/**
 * Framework-neutral initialization called by the API-102 {@link ModuleEntry} once per process.
 *
 * <p>纯 Java 版本：不做引擎探测、不加载任何 native 库。system_server 进程没有 Activity 环境，
 * 悬浮菜单只在 SystemUI 进程附加。阶段 5 的只读诊断 Hook 由 {@link Diagnostics} 安装。</p>
 */
public final class Bootstrap {
    private static volatile boolean started;
    private static volatile boolean overlayAttached;
    private static volatile boolean systemServerStarted;

    private Bootstrap() {}

    public static synchronized void start(Context context, XposedInterface api) {
        if (started || context == null) return;
        started = true;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;

        FeatureRegistry.initialize(app);

        // 阶段 5：只读诊断（仅在 system_server 生效；PROTECTIVE，不改变系统行为）。
        if (FeatureRegistry.getBool(FeatureRegistry.KEY_DIAGNOSTICS)) {
            Diagnostics.install(api, app);
        }

        // 阶段 6：范围选择增强（仅在 SystemUI 进程生效）。
        if (FeatureRegistry.getBool(FeatureRegistry.KEY_RANGE_CHOICE)
                && TemplateConfig.isSystemUiPackage(app.getPackageName())) {
            RangeChoiceHook.install(api, app);
        }

        // 多会话/范围选择等业务 Hook 由各 Hook 类在需要时读取 FeatureRegistry。
        if (TemplateConfig.ENABLE_OVERLAY) {
            attachOverlay(app);
        }
        if (TemplateConfig.VERBOSE_LOGS) {
            Log.i(TemplateConfig.LOG_TAG, "Bootstrap started in process=" + app.getPackageName());
        }
    }

    /**
     * system_server 专用初始化入口（无 Application/Context）。
     * 只安装只读诊断 Hook；不附加悬浮菜单、不初始化 FeatureRegistry 文件存储。
     *
     * @param api      libxposed API 实例
     * @param classLoader system_server 进程的类加载器（SystemServerStartingParam.getClassLoader()），
     *                    用于加载 MediaProjectionManagerService 等 system_server 内部类
     */
    public static synchronized void startForSystemServer(XposedInterface api, ClassLoader classLoader) {
        if (systemServerStarted || api == null) return;
        systemServerStarted = true;
        try {
            Diagnostics.installForSystemServer(api, classLoader);
            // 阶段 6b：自动授权（规则命中才生效，未配置规则时不改变任何行为）
            if (TemplateConfig.AUTO_GRANT_ENABLED) {
                android.content.Context ctx = getSystemServerContext();
                com.fairyxh.multiprojection.rule.AutoGrantHook.install(api, classLoader, ctx);
            }
            if (TemplateConfig.VERBOSE_LOGS) {
                Log.i(TemplateConfig.LOG_TAG, "Bootstrap started for system_server");
            }
        } catch (Throwable t) {
            Log.e(TemplateConfig.LOG_TAG, "system_server bootstrap failed", t);
        }
    }

    /** 反射获取 system_server 进程的全局 Context（ActivityThread 是 @hide，公共 SDK 编译不过）。 */
    private static android.content.Context getSystemServerContext() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            if (at != null) {
                Object app = atClass.getMethod("getApplication").invoke(at);
                if (app instanceof android.content.Context) {
                    return (android.content.Context) app;
                }
            }
        } catch (Throwable t) {
            Log.w(TemplateConfig.LOG_TAG, "getSystemServerContext failed: " + t);
        }
        return null;
    }

    private static void attachOverlay(Context app) {
        if (overlayAttached) return;
        overlayAttached = true;
        // Application.attach normally runs on the main thread. Register lifecycle callbacks
        // synchronously there so the first Activity.onResume cannot race ahead of us.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            OverlayController.attach(app);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> OverlayController.attach(app));
        }
    }
}