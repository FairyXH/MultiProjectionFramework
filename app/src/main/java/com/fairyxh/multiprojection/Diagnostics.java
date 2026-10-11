package com.fairyxh.multiprojection;

import android.content.Context;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 阶段 5：只读诊断 Hook。
 *
 * <p>仅在 system_server（packageName=android）进程生效。Hook {@code MediaProjectionManagerService}
 * 关键方法的入口/出口，仅记录有限频率的日志，<b>不修改任何系统行为</b>（ExceptionMode.PROTECTIVE）。</p>
 *
 * <p>脱敏约定：不打印 Binder Token，只打印 uid/packageName 等非敏感信息。</p>
 */
public final class Diagnostics {
    private static final String SERVER_CLASS = "com.android.server.media.projection.MediaProjectionManagerService";
    private static final String PROJECTION_INNER = "com.android.server.media.projection.MediaProjectionManagerService$MediaProjection";
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_MS = 1500L;
    private static volatile boolean installed;
    private static volatile long lastLogTs;

    private Diagnostics() {}

    /** 由 Bootstrap 调用；后台线程安装，避免阻塞进程启动。 */
    public static void install(final XposedInterface api, final Context context) {
        if (api == null || context == null) return;
        if (installed) return;
        installed = true;
        final String pkg = context.getPackageName();
        if (!"android".equals(pkg)) {
            if (TemplateConfig.VERBOSE_LOGS) {
                Log.i(TemplateConfig.LOG_TAG, "[Diag] skip server diag in process " + pkg);
            }
            return;
        }
        startInstallThread(api);
    }

    /** system_server 专用安装入口（由 Bootstrap.startForSystemServer 调用，无 Context）。 */
    public static void installForSystemServer(final XposedInterface api) {
        if (api == null) return;
        if (installed) return;
        installed = true;
        startInstallThread(api);
    }

    private static void startInstallThread(final XposedInterface api) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                tryInstall(api, MAX_ATTEMPTS);
            }
        }, "MPFw-Diag");
        t.setDaemon(true);
        t.start();
    }

    private static void tryInstall(final XposedInterface api, int attemptsRemaining) {
        try {
            Class<?> cls = findServerClass();
            if (cls == null) {
                if (attemptsRemaining > 1) {
                    try {
                        Thread.sleep(RETRY_MS);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    tryInstall(api, attemptsRemaining - 1);
                } else {
                    Log.w(TemplateConfig.LOG_TAG, "[Diag] class not found after retries: " + SERVER_CLASS);
                }
                return;
            }
            installHooks(api, cls);
            Log.i(TemplateConfig.LOG_TAG, "[Diag] server diagnostics installed");
        } catch (Throwable t) {
            Log.e(TemplateConfig.LOG_TAG, "[Diag] install failed", t);
        }
    }

    private static Class<?> findServerClass() {
        try {
            return Class.forName(SERVER_CLASS);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void installHooks(XposedInterface api, Class<?> cls) throws Throwable {
        ClassLoader cl = cls.getClassLoader();
        Class<?> mpClass = Class.forName(PROJECTION_INNER, false, cl);
        Class<?> crsClass = Class.forName("android.view.ContentRecordingSession", false, cl);

        hookEntryExit(api, cls.getDeclaredMethod("startProjectionLocked", mpClass),
                "startProjectionLocked", "proj");
        hookEntryExit(api, cls.getDeclaredMethod("stopProjectionLocked", mpClass),
                "stopProjectionLocked", "proj");
        hookEntryExit(api, cls.getDeclaredMethod("isCurrentProjection", IBinder.class),
                "isCurrentProjection", null);
        hookEntryExit(api, cls.getDeclaredMethod("setContentRecordingSession", crsClass),
                "setContentRecordingSession", "session");
        hookEntryExit(api, cls.getDeclaredMethod("getProjectionInternal", int.class, String.class),
                "getProjectionInternal", "uid,pkg");
        hookEntryExit(api, cls.getDeclaredMethod("createProjectionInternal",
                        int.class, String.class, int.class, boolean.class, UserHandle.class),
                "createProjectionInternal", "uid,pkg,type");
        hookEntryExit(api, cls.getDeclaredMethod("handleForegroundServicesChanged",
                        int.class, int.class, int.class),
                "handleForegroundServicesChanged", "pid,uid,types");
    }

    /**
     * 包装入口/出口日志。interceptor 直接使用 lambda（与模板 ModuleEntry 同款模式，避免显式
     * 引用可能不存在的内部类型）。使用 Object 数组反射读取参数。
     */
    private static void hookEntryExit(final XposedInterface api, final Method method,
                                      final String name, final String argDesc) {
        try {
            method.setAccessible(true);
            api.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object thiz = chain.getThisObject();
                        Object[] args = null;
                        try {
                            args = chain.getArgs() == null ? null : chain.getArgs().toArray();
                        } catch (Throwable ignored) {
                        }
                        log(name + " enter " + describe(thiz, args, argDesc));
                        Object result;
                        try {
                            result = chain.proceed();
                        } catch (Throwable t) {
                            Log.w(TemplateConfig.LOG_TAG, "[Diag] " + name + " threw " + t.getClass().getSimpleName());
                            throw t;
                        }
                        log(name + " exit result=" + (result == null ? "null" : result.getClass().getSimpleName()));
                        return result;
                    });
        } catch (Throwable t) {
            Log.w(TemplateConfig.LOG_TAG, "[Diag] hook " + name + " failed: " + t.getMessage());
        }
    }

    /** 限频日志（2 秒窗口内只记一次）。 */
    private static void log(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastLogTs < 2000L) return;
        lastLogTs = now;
        Log.i(TemplateConfig.LOG_TAG, "[Diag] " + msg);
    }

    private static String describe(Object thiz, Object[] args, String argDesc) {
        if (argDesc == null || args == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] keys = argDesc.split(",");
        for (int i = 0; i < keys.length && i < args.length; i++) {
            if (i > 0) sb.append(' ');
            Object a = args[i];
            String key = keys[i];
            if ("proj".equals(key)) sb.append(projectionInfo(a));
            else if ("session".equals(key)) sb.append(sessionInfo(a));
            else if ("uid".equals(key)) sb.append("uid=").append(a);
            else if ("pkg".equals(key)) sb.append("pkg=").append(a);
            else if ("type".equals(key)) sb.append("type=").append(a);
            else if ("pid".equals(key)) sb.append("pid=").append(a);
            else if ("types".equals(key)) sb.append("types=").append(a);
        }
        if (thiz != null) {
            sb.append(" svc=").append(thiz.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 从 MediaProjection 实例反射读取脱敏信息（uid/packageName）。 */
    private static String projectionInfo(Object proj) {
        if (proj == null) return "proj=null";
        try {
            Field uidF = proj.getClass().getField("uid");
            Field pkgF = proj.getClass().getField("packageName");
            return "proj(uid=" + uidF.getInt(proj) + " pkg=" + pkgF.get(proj) + ")";
        } catch (Throwable t) {
            return "proj=(" + proj.getClass().getSimpleName() + ")";
        }
    }

    /** 从 ContentRecordingSession 反射读取录制目标摘要。 */
    private static String sessionInfo(Object session) {
        if (session == null) return "session=null";
        try {
            Method getContent = session.getClass().getMethod("getContentToRecord");
            Method getTarget = session.getClass().getMethod("getTargetUid");
            return "session(content=" + getContent.invoke(session) + " targetUid=" + getTarget.invoke(session) + ")";
        } catch (Throwable t) {
            return "session=(" + session.getClass().getSimpleName() + ")";
        }
    }
}