package com.fairyxh.multiprojection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fairyxh.multiprojection.ui.OverlayController;

/**
 * Framework-neutral initialization called by the API-102 {@link ModuleEntry} once per process.
 *
 * <p>纯 Java 版本：不做引擎探测、不加载任何 native 库。system_server 进程没有 Activity 环境，
 * 悬浮菜单只在 SystemUI 进程附加。</p>
 */
public final class Bootstrap {
    private static volatile boolean started;
    private static volatile boolean overlayAttached;

    private Bootstrap() {}

    public static synchronized void start(Context context) {
        if (started || context == null) return;
        started = true;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;

        FeatureRegistry.initialize(app);

        // 多会话/范围选择等业务 Hook 由各 Hook 类在需要时读取 FeatureRegistry。
        if (TemplateConfig.ENABLE_OVERLAY) {
            attachOverlay(app);
        }
        if (TemplateConfig.VERBOSE_LOGS) {
            Log.i(TemplateConfig.LOG_TAG, "Bootstrap started in process=" + app.getPackageName());
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