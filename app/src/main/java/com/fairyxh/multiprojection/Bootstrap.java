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
     */
    public static synchronized void startForSystemServer(XposedInterface api) {
        if (systemServerStarted || api == null) return;
        systemServerStarted = true;
        try {
            Diagnostics.installForSystemServer(api);
            if (TemplateConfig.VERBOSE_LOGS) {
                Log.i(TemplateConfig.LOG_TAG, "Bootstrap started for system_server");
            }
        } catch (Throwable t) {
            Log.e(TemplateConfig.LOG_TAG, "system_server bootstrap failed", t);
        }
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