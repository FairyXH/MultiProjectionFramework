package com.fairyxh.multiprojection;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 模块全局配置（纯 Java 版）。
 *
 * <p>作用域严格限定为系统框架（{@code android} 包，system_server 进程）与
 * {@code com.android.systemui}；绝不注入任何第三方 App 进程。</p>
 */
public final class TemplateConfig {
    private TemplateConfig() {}

    /**
     * LSPosed 作用域：仅系统框架（android / system_server）与 SystemUI。
     * 第三方 App 一律不注入。
     */
    public static final String[] TARGET_PACKAGES = {
            "android",
            "com.android.systemui"
    };

    /**
     * 进程后缀白名单：system_server 主进程名为 "system"（包名 android），SystemUI 主进程为
     * "com.android.systemui"。{@code ""} 表示主进程（进程名==包名）。
     * 允许 "*" 表示所有进程（但作用域已限定上述两包）。
     */
    public static final String[] TARGET_PROCESS_SUFFIXES = {"", "system"};

    /** 永不注入的进程后缀（卫星进程等）。 */
    public static final String[] SKIP_PROCESS_SUFFIXES = {
            ":push", ":pushservice", ":pushcore",
            ":remote", ":service", ":core",
            ":msfcore", ":msf", ":mini",
            ":crashpad_handler", ":crash_handler", ":bugly", ":sentry",
            ":gameservice", ":anticheat", ":ac",
            ":sandboxed_process", ":isolated_process",
            ":privilege", ":stub"
    };

    public static final String MENU_BUBBLE_TEXT = "MP";
    public static final String MENU_TITLE = "MultiProjection Framework";
    public static final String MENU_SUBTITLE = "By FairyXH";

    /** 是否启用悬浮菜单（SystemUI 侧可见；system_server 无 Activity 环境不启用）。 */
    public static final boolean ENABLE_OVERLAY = true;

    /** 纯 Java 模块：不加载任何 native 库。 */
    public static final boolean ENABLE_NATIVE_SHADOWHOOK = false;

    /** Pulled from BuildConfig so release builds strip verbose logs automatically. */
    public static final boolean VERBOSE_LOGS = BuildConfig.VERBOSE_LOGS;

    /** 诊断日志总开关（独立于 VERBOSE_LOGS，默认开启以支持阶段 5 只读诊断）。 */
    public static final boolean DIAGNOSTICS_ENABLED = true;

    /** 多会话系统服务改造总开关（阶段 7：多 App 同时录制整屏/单应用，互不顶掉；高优先级实验功能）。 */
    public static final boolean MULTI_SESSION_ENABLED = true;

    /** SystemUI 录制范围选择增强（阶段 6，验证期默认开启；正式发布前可改回 false 由悬浮菜单控制）。 */
    public static final boolean RANGE_CHOICE_ENHANCED = true;

    /**
     * 自动授权（阶段 6b）：规则内 App 发起 MediaProjection 时自动授权、不弹窗。
     * 规则列表由模块 App 配置页写入 files/rules.json。默认开启（仅当规则命中才生效，
     * 未配置规则时不影响任何 App）。
     */
    public static final boolean AUTO_GRANT_ENABLED = true;

    /**
     * Log tag used everywhere in the module. Keep it bland; module-branded tags are trivially
     * greppable in logcat by target-app integrity sweeps.
     */
    public static final String LOG_TAG = "MPFw";

    /** Thread name used for deferred module initialization inside target processes. */
    public static final String WORKER_THREAD_NAME = "MPFwWorker";

    /** Best-effort runtime toggle state filename under the target app's files directory. */
    public static final String FEATURE_STATE_FILE_NAME = ".rt_state";

    public static boolean shouldHook(String packageName) {
        if (packageName == null) return false;
        for (String target : TARGET_PACKAGES) {
            if (packageName.equals(target)) return true;
        }
        return false;
    }

    /** 是否为 SystemUI 进程（阶段 6 范围选择 Hook 的应用条件）。 */
    public static boolean isSystemUiPackage(String packageName) {
        return "com.android.systemui".equals(packageName);
    }

    /**
     * Process-level filter applied after {@link #shouldHook(String)}. The {@code processName}
     * argument is the value returned by {@code ModuleLoadedParam.getProcessName()}.
     */
    public static boolean shouldHookProcess(String packageName, String processName) {
        if (!shouldHook(packageName)) return false;
        String suffix = processSuffix(packageName, processName);

        List<String> skip = Arrays.asList(SKIP_PROCESS_SUFFIXES);
        if (skip.contains(suffix)) return false;

        List<String> allow = TARGET_PROCESS_SUFFIXES == null
                ? Collections.emptyList()
                : Arrays.asList(TARGET_PROCESS_SUFFIXES);
        if (allow.isEmpty()) return suffix.isEmpty();
        if (allow.contains("*")) return true;
        return allow.contains(suffix);
    }

    private static String processSuffix(String packageName, String processName) {
        if (processName == null || processName.isEmpty()) return "";
        if (packageName == null) return processName;
        if (!processName.startsWith(packageName)) return processName;
        return processName.substring(packageName.length());
    }
}
