package com.fairyxh.multiprojection.rule;

import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 阶段 6b：自动授权 Hook（system_server 侧）。
 *
 * <p>核心机制（已反编译确认）：SystemUI 的 {@code MediaProjectionPermissionActivity.onCreate()}
 * 中，当 {@code MediaProjectionServiceHelper.hasProjectionPermission(uid, pkg)} 返回 true 时，
 * 直接走 {@code createOrReuseProjection + setResult(-1) + finish()} —— <b>完全不弹窗</b>。
 * 该 Binder 调用的服务端实现是 {@code MediaProjectionManagerService$BinderService
 * .hasProjectionPermission(uid, pkg)}，仅检查 CAPTURE_VIDEO_OUTPUT 权限 + AppOps(OP_PROJECT_MEDIA)。
 * 正常第三方 App 该权限必然为 false → 进入弹窗。Hook 它命中规则时返回 true，即可让规则内 App
 * 自动授权、跳过弹窗。</p>
 *
 * <p>单应用模式（可选）：在 {@code createProjectionInternal} 创建 projection 后，若规则为
 * 单应用，通过 ActivityTaskManager 查询目标包前台 task，取 task 的 WindowContainerToken（binder
 * 即 WindowContainer.RemoteToken），构造 {@code ContentRecordingSession.createTaskSession(token,
 * taskId)} 反射写入 projection 的 mSession 字段；App 建 VirtualDisplay 后系统自动
 * {@code notifyVirtualDisplayCreated} → 自动把该 session 推给 WMS → 只录制目标 task。</p>
 *
 * <p>安全设计：仅当规则命中（包名精确匹配）且目标 task 存在时才生效；任何异常都 PROTECTIVE
 * 放行原逻辑（返回 false → 走正常弹窗）。</p>
 */
public final class AutoGrantHook {
    private static final String TAG = "MPFw-AutoGrant";
    private static final String SERVER_CLASS = "com.android.server.media.projection.MediaProjectionManagerService";
    private static final String BINDER_SERVICE = "com.android.server.media.projection.MediaProjectionManagerService$BinderService";
    private static final String PROJECTION_INNER = "com.android.server.media.projection.MediaProjectionManagerService$MediaProjection";
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_MS = 1500L;
    private static volatile boolean installed;
    private static volatile XposedInterface sApi;
    private static volatile ClassLoader sClassLoader;
    private static volatile android.content.Context sContext;

    private AutoGrantHook() {}

    /** system_server 专用安装入口（由 Bootstrap.startForSystemServer 调用）。 */
    public static void install(XposedInterface api, ClassLoader classLoader, android.content.Context context) {
        if (api == null) return;
        if (installed) return;
        installed = true;
        sApi = api;
        sClassLoader = classLoader;
        sContext = context;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                tryInstall(MAX_ATTEMPTS);
            }
        }, "MPFw-AutoGrant");
        t.setDaemon(true);
        t.start();
    }

    private static void tryInstall(int attemptsRemaining) {
        try {
            Class<?> cls = findServerClass();
            if (cls == null) {
                logW("class not found: " + SERVER_CLASS + " (attempts left=" + attemptsRemaining + ")");
                if (attemptsRemaining > 1) {
                    Thread.sleep(RETRY_MS);
                    tryInstall(attemptsRemaining - 1);
                }
                return;
            }
            installHooks(cls);
            logI("AutoGrantHook installed");
        } catch (Throwable t) {
            logE("install failed", t);
        }
    }

    private static Class<?> findServerClass() {
        try {
            if (sClassLoader != null) {
                return Class.forName(SERVER_CLASS, false, sClassLoader);
            }
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(SERVER_CLASS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void installHooks(Class<?> serverCls) throws Throwable {
        ClassLoader cl = serverCls.getClassLoader();
        Class<?> binderService = Class.forName(BINDER_SERVICE, false, cl);
        Class<?> projection = Class.forName(PROJECTION_INNER, false, cl);
        Class<?> crs = Class.forName("android.view.ContentRecordingSession", false, cl);

        // 1. hasProjectionPermission(uid, pkg) -> true（命中规则时）=> 自动授权不弹窗
        final Method hasPerm = binderService.getDeclaredMethod("hasProjectionPermission", int.class, String.class);
        hasPerm.setAccessible(true);
        sApi.hook(hasPerm)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object[] args = chain.getArgs() == null ? null : chain.getArgs().toArray();
                        int uid = args != null && args.length > 0 && args[0] instanceof Integer
                                ? (Integer) args[0] : -1;
                        String pkg = args != null && args.length > 1 ? String.valueOf(args[1]) : null;
                        RulesConfig.Rule rule = RuleStore.findRule(sContext, pkg);
                        if (rule != null) {
                            logI("auto-grant hit: uid=" + uid + " pkg=" + pkg
                                    + " mode=" + modeName(rule.mode));
                            return Boolean.TRUE;
                        }
                        return result;
                    } catch (Throwable t) {
                        logW("hasProjectionPermission handler failed: " + t);
                        return result;
                    }
                });

        // 2. createProjectionInternal 返回后：单应用规则 -> 预置 task session
        final Method create = serverCls.getDeclaredMethod("createProjectionInternal",
                int.class, String.class, int.class, boolean.class, UserHandle.class);
        create.setAccessible(true);
        sApi.hook(create)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object[] args = chain.getArgs() == null ? null : chain.getArgs().toArray();
                        String pkg = args != null && args.length > 1 ? String.valueOf(args[1]) : null;
                        RulesConfig.Rule rule = RuleStore.findRule(sContext, pkg);
                        if (rule != null && rule.mode != RulesConfig.MODE_FULL_SCREEN && result != null) {
                            injectTaskSession(result, projection, crs, rule);
                        }
                        return result;
                    } catch (Throwable t) {
                        logW("createProjectionInternal handler failed: " + t);
                        return result;
                    }
                });
    }

    /** 为单应用规则预置 task session 到 projection.mSession（反射，避免编译期依赖内部类型）。 */
    private static void injectTaskSession(Object projection, Class<?> projectionCls, Class<?> crsCls,
                                          RulesConfig.Rule rule) throws Throwable {
        String targetPkg = RulesConfig.effectiveTargetPkg(rule);
        if (targetPkg == null || targetPkg.isEmpty()) return;

        // 通过 ActivityTaskManager.getService().getTasks() 查目标包前台 task
        Object taskInfo = findForegroundTask(targetPkg);
        if (taskInfo == null) {
            logW("no foreground task for target=" + targetPkg);
            return;
        }
        IBinder token = taskToken(taskInfo);
        int taskId = taskId(taskInfo);
        if (token == null) {
            logW("no token for target task pkg=" + targetPkg + " taskId=" + taskId);
            return;
        }

        // ContentRecordingSession.createTaskSession(IBinder token, int taskId)
        Object session = crsCls.getMethod("createTaskSession", IBinder.class, int.class)
                .invoke(null, token, taskId);

        // projection.mSession = session（反射写字段）
        Field sessionField = projectionCls.getDeclaredField("mSession");
        sessionField.setAccessible(true);
        sessionField.set(projection, session);

        logI("injected task session: target=" + targetPkg + " taskId=" + taskId
                + " token=" + token.getClass().getSimpleName());
    }

    private static Object findForegroundTask(String targetPkg) {
        try {
            Method getService = android.app.ActivityManager.class.getMethod("getService");
            Object atms = getService.invoke(null);
            Method getTasks = null;
            for (Method m : atms.getClass().getMethods()) {
                if ("getTasks".equals(m.getName()) && m.getParameterCount() == 4
                        && int.class == m.getParameterTypes()[0]
                        && boolean.class == m.getParameterTypes()[1]
                        && boolean.class == m.getParameterTypes()[2]
                        && int.class == m.getParameterTypes()[3]) {
                    getTasks = m;
                    break;
                }
            }
            if (getTasks == null) {
                logW("getTasks(4) not found on IActivityTaskManager");
                return null;
            }
            List<?> tasks = (List<?>) getTasks.invoke(atms, 100, true, false, -1);
            if (tasks == null) return null;
            for (Object t : tasks) {
                Object top = topActivity(t);
                if (top != null && targetPkg.equals(topActivityPackage(top))) {
                    return t;
                }
            }
            // 若按 topActivity 未命中，尝试 baseActivity 兜底
            for (Object t : tasks) {
                Object base = baseActivity(t);
                if (base != null && targetPkg.equals(topActivityPackage(base))) {
                    return t;
                }
            }
            return null;
        } catch (Throwable t) {
            logW("findForegroundTask failed: " + t);
            return null;
        }
    }

    // ---- 反射辅助 ----
    // RunningTaskInfo/TaskInfo 的 topActivity/baseActivity/token 是 public 字段（TaskInfo 继承），
    // 兼容 getter 与字段两种形态。

    private static Object topActivity(Object taskInfo) throws Throwable {
        return readTaskField(taskInfo, "topActivity", "getTopActivity");
    }

    private static Object baseActivity(Object taskInfo) throws Throwable {
        return readTaskField(taskInfo, "baseActivity", "getBaseActivity");
    }

    private static Object readTaskField(Object taskInfo, String fieldName, String getterName) throws Throwable {
        Class<?> c = taskInfo.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getField(fieldName);
                return f.get(taskInfo);
            } catch (NoSuchFieldException ignored) {
                try {
                    Method m = c.getMethod(getterName);
                    return m.invoke(taskInfo);
                } catch (NoSuchMethodException ignored2) {
                    c = c.getSuperclass();
                }
            }
        }
        return null;
    }

    private static String topActivityPackage(Object activity) throws Throwable {
        if (activity == null) return null;
        // TaskInfo.topActivity/baseActivity 是 ComponentName，可直接 getPackageName()
        try {
            Method getPkg = activity.getClass().getMethod("getPackageName");
            Object pkg = getPkg.invoke(activity);
            return pkg == null ? null : pkg.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static IBinder taskToken(Object taskInfo) throws Throwable {
        // TaskInfo 的 token 字段是 WindowContainerToken（public，从 TaskInfo 继承）
        Class<?> c = taskInfo.getClass();
        Object token = null;
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getField("token");
                token = f.get(taskInfo);
                break;
            } catch (NoSuchFieldException ignored) {
                try {
                    token = c.getMethod("getToken").invoke(taskInfo);
                    break;
                } catch (NoSuchMethodException ignored2) {
                    c = c.getSuperclass();
                }
            }
        }
        if (token instanceof IBinder) return (IBinder) token;
        if (token != null) {
            // WindowContainerToken（Parcelable）——取它的 asBinder()
            try {
                Method asBinder = token.getClass().getMethod("asBinder");
                Object binder = asBinder.invoke(token);
                if (binder instanceof IBinder) return (IBinder) binder;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int taskId(Object taskInfo) throws Throwable {
        try {
            // TaskInfo 有 taskId 字段（RunningTaskInfo 从 TaskInfo 继承）
            Class<?> c = taskInfo.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getField("taskId");
                    Object v = f.get(taskInfo);
                    if (v instanceof Integer) return (Integer) v;
                    break;
                } catch (NoSuchFieldException ignored) {
                    try {
                        Object v = c.getMethod("getTaskId").invoke(taskInfo);
                        if (v instanceof Integer) return (Integer) v;
                    } catch (NoSuchMethodException ignored2) {
                        c = c.getSuperclass();
                    }
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- 日志 ----

    private static String modeName(int mode) {
        switch (mode) {
            case RulesConfig.MODE_FULL_SCREEN: return "full_screen";
            case RulesConfig.MODE_SINGLE_SELF: return "single_self";
            case RulesConfig.MODE_SINGLE_TARGET: return "single_target";
            default: return "unknown(" + mode + ")";
        }
    }

    private static void logI(String msg) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
        Log.i("MPFw", "[AutoGrant] " + msg);
    }

    private static void logW(String msg) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.WARN, TAG, msg);
        } catch (Throwable ignored) {
        }
        Log.w("MPFw", "[AutoGrant] " + msg);
    }

    private static void logE(String msg, Throwable t) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.ERROR, TAG, msg + " #" + t);
        } catch (Throwable ignored) {
        }
        Log.e("MPFw", "[AutoGrant] " + msg, t);
    }
}