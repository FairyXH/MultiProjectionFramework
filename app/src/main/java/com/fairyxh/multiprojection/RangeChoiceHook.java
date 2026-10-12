package com.fairyxh.multiprojection;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 阶段 6：SystemUI 范围选择 Hook。
 *
 * <p>仅作用于 {@code com.android.systemui} 进程。Hook
 * {@code MediaProjectionPermissionDialogDelegate.Companion.createOptionList}：当请求方配置
 * {@code CAPTURE_REGION_FIXED_DISPLAY}（固定整屏）时，原逻辑会禁用"单个应用"选项
 * （{@code spinnerDisabledText != null}）并把列表反转（整屏在前）。</p>
 *
 * <p>Hook 策略：<b>保持原有列表顺序与默认选中项不变</b>。FIXED_DISPLAY 请求时原逻辑会反转列表
 * （整屏在前且为默认选中）——我们<b>不撤销该反转</b>，仅把 {@code mode==0}（单应用）选项替换为
 * {@code spinnerDisabledText=null} 的新实例。这样：整屏仍是默认选中（符合 FIXED_DISPLAY 请求方
 * 预期），同时用户可下拉改成"单个应用"。USER_CHOICE 请求（区域==0）本就未禁用单应用，不受影响。</p>
 *
 * <p>黑名单（pubgmhd/sgame）说明：黑名单在 {@code MediaProjectionPermissionActivity.
 * getMediaProjectionConfig()} 强制走 FIXED_DISPLAY 整屏配置并单槽显示；本 Hook 对黑名单同样会
 * 放开单应用选项（因无法在此拿到包名区分）。如需对黑名单保持强制整屏，需额外按包名过滤（见
 * DESIGN_IMPLEMENTATION.md 待批准项）。</p>
 *
 * <p>只读增强，不修改任何系统行为；异常时 PROTECTIVE 放行原逻辑。</p>
 */
public final class RangeChoiceHook {
    private static final String COMPANION_CLASS =
            "com.android.systemui.mediaprojection.permission.MediaProjectionPermissionDialogDelegate$Companion";
    private static final String METHOD_NAME = "createOptionList";
    private static volatile boolean installed;
    private static volatile XposedInterface sApi;

    private RangeChoiceHook() {}

    /** 由 Bootstrap 在 SystemUI 进程调用（有 Context，可拿到 SystemUI 的 ClassLoader）。 */
    public static void install(XposedInterface api, Context context) {
        if (api == null || context == null) return;
        if (installed) return;
        installed = true;
        sApi = api;
        try {
            Class<?> companion = Class.forName(COMPANION_CLASS, false, context.getClassLoader());
            Method create = findCreateOptionList(companion);
            if (create == null) {
                logW("createOptionList method not found on " + COMPANION_CLASS);
                return;
            }
            api.hook(create)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        // 运行日志：记录请求参数，便于验证时确认 regionToCapture 与 appName
                        try {
                            Object config = chain.getArg(2);
                            Object appName = chain.getArg(1);
                            Object forceShow = chain.getArg(3);
                            int region = -1;
                            if (config != null) {
                                java.lang.reflect.Method getRegion = config.getClass().getMethod("getRegionToCapture");
                                region = ((Integer) getRegion.invoke(config));
                            }
                            Object out = enableSingleAppOption(result);
                            int size = (out instanceof List) ? ((List<?>) out).size() : -1;
                            logI("createOptionList invoked: appName=" + appName
                                    + " region=" + region + " forceShow=" + forceShow
                                    + " -> resultSize=" + size + " (hook applied)");
                            return out;
                        } catch (Throwable t) {
                            logW("createOptionList handler failed: " + t);
                            return result;
                        }
                    });
            logI("RangeChoiceHook installed: " + COMPANION_CLASS + "#" + METHOD_NAME);
        } catch (Throwable t) {
            logW("RangeChoiceHook install failed: " + t);
        }
    }

    /** 遍历查找 4 参数 createOptionList（Context, String, MediaProjectionConfig, boolean）。 */
    private static Method findCreateOptionList(Class<?> companion) {
        for (Method m : companion.getDeclaredMethods()) {
            if (METHOD_NAME.equals(m.getName()) && m.getParameterCount() == 4) {
                return m;
            }
        }
        return null;
    }

    /**
     * 处理 createOptionList 的返回值：克隆列表；对 mode==0（单应用）且被禁用
     * （spinnerDisabledText != null）的 ScreenShareOption，替换为 spinnerDisabledText=null 的新实例。
     * 保持元素顺序不变 → 默认选中项与反转逻辑不受影响，仅解除"单应用"禁用态。
     */
    private static Object enableSingleAppOption(Object original) {
        if (!(original instanceof List)) return original;
        try {
            List<?> list = (List<?>) original;
            List<Object> out = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object item : list) {
                if (item == null) {
                    out.add(null);
                    continue;
                }
                out.add(clearDisabledIfSingleApp(item));
                if (out.get(out.size() - 1) != item) changed = true;
            }
            return changed ? out : original;
        } catch (Throwable t) {
            logW("enableSingleAppOption failed: " + t);
            return original;
        }
    }

    /** 若该项是单应用（mode==0）且被禁用则返回新实例（disabled=null），否则原样返回。 */
    private static Object clearDisabledIfSingleApp(Object item) throws Throwable {
        Class<?> c = item.getClass();
        Object modeObj = c.getMethod("getMode").invoke(item);
        if (modeObj instanceof Integer && ((Integer) modeObj) == 0) {
            Object disabled = c.getMethod("getSpinnerDisabledText").invoke(item);
            if (disabled != null) {
                // ScreenShareOption(int mode, int spinnerText, int warningText, String spinnerDisabledText)
                Object spinnerText = c.getMethod("getSpinnerText").invoke(item);
                Object warningText = c.getMethod("getWarningText").invoke(item);
                return c.getConstructor(int.class, int.class, int.class, String.class)
                        .newInstance(0, spinnerText, warningText, null);
            }
        }
        return item;
    }

    // ---- LSPosed log() API 为主，android.util.Log 兜底（与 Diagnostics 同款，保证 system_server 可观测性） ----

    private static void logI(String msg) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.INFO, TemplateConfig.LOG_TAG, "[RangeChoice] " + msg);
        } catch (Throwable ignored) {
        }
        Log.i(TemplateConfig.LOG_TAG, "[RangeChoice] " + msg);
    }

    private static void logW(String msg) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.WARN, TemplateConfig.LOG_TAG, "[RangeChoice] " + msg);
        } catch (Throwable ignored) {
        }
        Log.w(TemplateConfig.LOG_TAG, "[RangeChoice] " + msg);
    }
}