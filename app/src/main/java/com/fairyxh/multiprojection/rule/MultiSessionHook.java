package com.fairyxh.multiprojection.rule;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 阶段 7：多会话 Hook（system_server 侧）—— 让多个 App 可同时持有独立投影会话、互不顶掉。
 *
 * <p><b>问题实证</b>（设备日志 2026-10-14）：15:19:16 搜题酱发起投影时，夸克投影被原生
 * {@code startProjectionLocked} 强制 stop；随后夸克再点又把搜题酱顶掉（Binder death → stop），
 * 两者反复互踢，导致"用一个掉另一个的权限"、搜题酱再点崩溃。</p>
 *
 * <p><b>根因</b>：ColorOS {@code MediaProjectionManagerService} 是"双轨单槽"——
 * {@code mProjectionGrant} 只有一个 slot，新 App 启动必然 {@code mProjectionGrant.stop()} 旧 App。</p>
 *
 * <p><b>方案：平移出槽（不接管原生核心逻辑，仅平移旧投影）</b>：
 * <ol>
 *   <li><b>多会话表</b>：进程内静态 {@code Map<IBinder token, Object projection>}，
 *       保存"已出槽但仍在录制"的旧投影。</li>
 *   <li><b>startProjectionLocked</b>：proceed() 前若已有 {@code mProjectionGrant}，先把它
 *       平移进表并<b>暂时清空单槽字段</b> → 原生看到 null 就不 stop 旧投影；proceed() 让
 *       新投影正常入槽 + dispatchStart；再把新投影也入表。</li>
 *   <li><b>stopProjectionLocked</b>：proj == 当前槽 → 走原生（清槽 + dispatchStop）；
 *       否则是表内投影 → 只移除自己 + 反射 dispatchStop（原生会无条件清空单槽字段，
 *       会连累正在录制的其他会话，故不能走原生）。</li>
 *   <li><b>isCurrentProjection</b>：token 在表内 → true（否则表内投影 stop() 开头
 *       isCurrentProjection 检查失败，永远无法停止，泄漏）。</li>
 * </ol></p>
 *
 * <p><b>并行录制边界</b>：日志实证每个 App 的 VirtualDisplay 是不同 display id；WMS 每
 * DisplayContent 有独立 ContentRecorder/ContentRecordingController（反编译确认），不同 VD 的
 * session 不会互踢。同一真实 Display 被多个 VD 同时镜像是否可行受 SurfaceFlinger 限制，
 * 需真机实测（docs/SESSION_MODEL.md 第 4 节）。</p>
 *
 * <p><b>安全</b>：全部 {@code ExceptionMode.PROTECTIVE}；仅对普通 App 投影平移/移除；
 * 任何异常放行原逻辑（回退到原生单槽），不影响系统。默认由
 * {@code TemplateConfig.MULTI_SESSION_ENABLED} 控制开关。</p>
 */
public final class MultiSessionHook {
    private static final String TAG = "MPf-MultiSession";
    private static final String SERVER_CLASS = "com.android.server.media.projection.MediaProjectionManagerService";
    private static final String PROJECTION_INNER = "com.android.server.media.projection.MediaProjectionManagerService$MediaProjection";
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_MS = 1500L;

    private static volatile boolean installed;
    private static volatile XposedInterface sApi;
    private static volatile ClassLoader sClassLoader;

    /** 多会话表：出槽但仍在录制的投影（token → projection 对象）。 */
    private static final Map<IBinder, Object> sTable = new ConcurrentHashMap<>();

    private MultiSessionHook() {}

    /** system_server 专用安装入口（由 Bootstrap.startForSystemServer 调用）。 */
    public static void install(XposedInterface api, ClassLoader classLoader) {
        if (api == null) return;
        if (installed) return;
        installed = true;
        sApi = api;
        sClassLoader = classLoader;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                tryInstall(MAX_ATTEMPTS);
            }
        }, "MPf-MultiSession");
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
            logI("MultiSessionHook installed");
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
        Class<?> projection = Class.forName(PROJECTION_INNER, false, cl);

        // ---- 1. startProjectionLocked：平移旧投影出槽，避免原生 stop ----------------
        final Method startProj = serverCls.getDeclaredMethod("startProjectionLocked", projection);
        startProj.setAccessible(true);
        sApi.hook(startProj)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> onStartProjectionLocked(chain, projection));

        // ---- 2. stopProjectionLocked：表内投影只移除自己（不连累当前槽） ----------------
        final Method stopProj = serverCls.getDeclaredMethod("stopProjectionLocked", projection);
        stopProj.setAccessible(true);
        sApi.hook(stopProj)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> onStopProjectionLocked(chain, projection));

        // ---- 3. isCurrentProjection：表内 token 且未停止（mToken!=null）→ true ----
        final Method isCurrent = serverCls.getDeclaredMethod("isCurrentProjection", IBinder.class);
        isCurrent.setAccessible(true);
        sApi.hook(isCurrent)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object[] args = chain.getArgs() == null ? null : chain.getArgs().toArray();
                        if (args != null && args.length > 0 && args[0] instanceof IBinder) {
                            IBinder token = (IBinder) args[0];
                            if (isActiveInTable(token)) {
                                return Boolean.TRUE;
                            }
                        }
                        return result;
                    } catch (Throwable t) {
                        logW("isCurrentProjection handler failed: " + t);
                        return result;
                    }
                });

        // ---- 4. MediaProjection.stop：二次 stop 防护（mToken==null 直接返回，避免 NPE）----
        final Method mpStop = projection.getDeclaredMethod("stop");
        mpStop.setAccessible(true);
        sApi.hook(mpStop)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try {
                        Object thiz = chain.getThisObject();
                        Object token = readField(thiz, "mToken");
                        if (token == null) {
                            // 已 stop 过的投影再次 stop：原生会 unlinkToDeath(null) NPE。
                            // 多会话下 isCurrentProjection 可能仍返回 true（表内残留），
                            // 这里直接短路，避免夸克 onUnbind 二次 stop 崩溃。
                            logI("stop() called on already-stopped projection (mToken=null) - short-circuit");
                            return null;
                        }
                    } catch (Throwable t) {
                        logW("stop guard failed: " + t);
                    }
                    return chain.proceed();
                });

        logI("MultiSessionHook installed (4 core hooks)");
    }

    // ---- 核心处理器（用 Object chain 参数，避免编译依赖非公开 HookCallback 类型） ----

    private static Object onStartProjectionLocked(Object chain, Class<?> projectionCls) {
        try {
            Object proj = chainArg0(chain);
            if (proj == null) {
                return proceed(chain);
            }
            String pkg = pkgOf(proj);
            if ("com.oplus.screenrecorder".equals(pkg)) {
                return proceed(chain); // oplus 录屏双轨不接管
            }

            // 1. 若已有当前投影（非 oplus），先平移出槽并暂时清空单槽字段，避免原生 stop。
            //    【关键】从 proj（MediaProjection 内部类）反射取 this$0 外层 service，
            //    不依赖 hook 回调的 getThisObject（对 Nest$ 方法可能返回 null）。
            Object svc = outerService(proj);
            if (svc != null) {
                Object old = readField(svc, "mProjectionGrant");
                if (old != null && old != proj && !"com.oplus.screenrecorder".equals(pkgOf(old))) {
                    Object oldToken = readField(old, "mToken");
                    if (oldToken == null) {
                        // 旧投影已死（mToken=null）：直接清残留字段 + 移除表，
                        // 否则原生 start 195 行 mProjectionGrant.stop() 二次 stop → NPE
                        removeFromTable(old);
                        writeField(svc, "mProjectionGrant", null);
                        writeField(svc, "mProjectionToken", null);
                        logI("startProjectionLocked drop-dead: pkg=" + pkgOf(old)
                                + " (mToken=null) cleared, table=" + sTable.size());
                    } else {
                        // 旧投影仍活跃：平移出槽 + 模拟停止（mToken=null），
                        // 原生 start 195 行 stop() 会命中第4 hook 短路 → 不 NPE
                        addToTable(old);
                        writeField(old, "mToken", null);
                        writeField(svc, "mProjectionGrant", null);
                        writeField(svc, "mProjectionToken", null);
                        logI("startProjectionLocked evict-pre: pkg=" + pkgOf(old)
                                + " moved to multi table (mToken=null, table=" + sTable.size() + ")");
                    }
                }
            }

            // 2. 让原生 proceed()：新投影正常入槽 + dispatchStart（原生看到 mProjectionGrant=null 不 stop）
            Object result = proceed(chain);

            // 3. 新投影也入表（保证后续 isCurrentProjection/stop 都能定位）
            addToTable(proj);
            logI("startProjectionLocked multi: pkg=" + pkg
                    + " started (table=" + sTable.size() + ")");
            return result;
        } catch (Throwable t) {
            logW("onStartProjectionLocked failed, fallback: " + t);
            return proceed(chain);
        }
    }

    private static Object onStopProjectionLocked(Object chain, Class<?> projectionCls) {
        try {
            Object proj = chainArg0(chain);
            if (proj == null) {
                return proceed(chain);
            }
            String pkg = pkgOf(proj);
            if ("com.oplus.screenrecorder".equals(pkg)) {
                return proceed(chain);
            }

            // 【关键】从 proj（MediaProjection 内部类）反射取 this$0 外层 service，
            // 不依赖 hook 回调的 getThisObject（对 Nest$ 方法可能返回 null）。
            Object svc = outerService(proj);
            boolean isCurrent = svc != null && readField(svc, "mProjectionGrant") == proj;
            if (!isCurrent) {
                // 表内投影停止：只移除自己 + dispatchStop，不连累当前槽。
                // 【关键】必须同时清空单槽字段中属于该投影的残留：
                // 如果 mProjectionToken == proj.asBinder()（该投影曾是最新/当前槽，后被平移），
                // 不清空会导致 isCurrentProjection 永远 true -> 二次 stop NPE（夸克崩溃根因）。
                IBinder token = asBinderOf(proj);
                if (token != null && svc != null) {
                    Object curToken = readField(svc, "mProjectionToken");
                    if (token.equals(curToken)) {
                        writeField(svc, "mProjectionToken", null);
                        writeField(svc, "mProjectionGrant", null);
                    }
                }
                removeFromTable(proj);
                invokeDispatchStop(chain, svc, proj);
                logI("stopProjectionLocked multi: pkg=" + pkg
                        + " stopped (table=" + sTable.size() + ")");
                return null;
            }
            // 当前槽：走原生（清槽 + dispatchStop），同时从表移除
            Object result = proceed(chain);
            removeFromTable(proj);
            logI("stopProjectionLocked current: pkg=" + pkg + " stopped (table=" + sTable.size() + ")");
            return result;
        } catch (Throwable t) {
            logW("onStopProjectionLocked failed, fallback: " + t);
            return proceed(chain);
        }
    }

    // ---- 反射辅助 ----

    /** 从 MediaProjection 内部类实例反射取外层 MediaProjectionManagerService（this$0 字段）。 */
    private static Object outerService(Object proj) {
        if (proj == null) return null;
        try {
            for (Class<?> c = proj.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("this$0");
                    f.setAccessible(true);
                    Object svc = f.get(proj);
                    if (svc != null) return svc;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable t) {
            logW("outerService failed: " + t);
        }
        return null;
    }

    /** 取 hook 回调的 this（即被 hook 方法所属实例）。 */
    private static Object chainThis(Object chain) {
        try {
            return chain.getClass().getMethod("getThisObject").invoke(chain);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 表内 token 且投影未停止（mToken != null）才算活跃。 */
    private static boolean isActiveInTable(IBinder token) {
        Object proj = sTable.get(token);
        if (proj == null) return false;
        Object mToken = readField(proj, "mToken");
        return mToken != null;
    }

    /** 取 hook 回调第 1 个参数（投影实例）。 */
    private static Object chainArg0(Object chain) {
        try {
            Object[] args = chainArgs(chain);
            return args != null && args.length > 0 ? args[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object[] chainArgs(Object chain) {
        try {
            Object v = chain.getClass().getMethod("getArgs").invoke(chain);
            if (v == null) return null;
            return (Object[]) v.getClass().getMethod("toArray").invoke(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用原方法（chain.proceed()）。 */
    private static Object proceed(Object chain) {
        try {
            return chain.getClass().getMethod("proceed").invoke(chain);
        } catch (Throwable t) {
            throw new RuntimeException("proceed failed", t);
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void writeField(Object target, String name, Object value) {
        if (target == null) return;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return;
            }
        }
    }

    private static void addToTable(Object proj) {
        try {
            IBinder token = asBinderOf(proj);
            if (token != null) {
                sTable.put(token, proj);
            }
        } catch (Throwable t) {
            logW("addToTable failed: " + t);
        }
    }

    private static void removeFromTable(Object proj) {
        try {
            IBinder token = asBinderOf(proj);
            if (token != null) {
                sTable.remove(token);
            }
        } catch (Throwable t) {
            logW("removeFromTable failed: " + t);
        }
    }

    private static IBinder asBinderOf(Object proj) {
        try {
            Object v = proj.getClass().getMethod("asBinder").invoke(proj);
            return v instanceof IBinder ? (IBinder) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射调用 dispatchStop（多会话表内投影停止时通知 watcher/client）。 */
    private static void invokeDispatchStop(Object chain, Object svc, Object proj) {
        try {
            if (svc == null) {
                svc = chainThis(chain);
            }
            if (svc == null) {
                svc = outerService(proj);
            }
            if (svc == null) return;
            Method m = null;
            for (Class<?> c = svc.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("dispatchStop", proj.getClass());
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (m == null) return;
            m.setAccessible(true);
            m.invoke(svc, proj);
        } catch (Throwable t) {
            logW("invokeDispatchStop failed: " + t);
        }
    }

    private static String pkgOf(Object proj) {
        if (proj == null) return "?";
        try {
            Object v = proj.getClass().getField("packageName").get(proj);
            return v == null ? "?" : v.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---- 日志 ----

    private static void logI(String msg) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
        Log.i("MPFw", "[MultiSession] " + msg);
    }

    private static void logW(String msg) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.WARN, TAG, msg);
        } catch (Throwable ignored) {
        }
        Log.w("MPFw", "[MultiSession] " + msg);
    }

    private static void logE(String msg, Throwable t) {
        try {
            XposedInterface api = sApi;
            if (api != null) api.log(Log.ERROR, TAG, msg + " #" + t);
        } catch (Throwable ignored) {
        }
        Log.e("MPFw", "[MultiSession] " + msg, t);
    }
}
