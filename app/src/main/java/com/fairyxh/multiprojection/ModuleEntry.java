package com.fairyxh.multiprojection;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Modern libxposed API 102 entry point.
 *
 * <p>Registered in {@code META-INF/xposed/java_init.list}. Vector and LSPatch 1.2 call this class in
 * each scoped target process. Process-level filtering is enforced here (see
 * {@link TemplateConfig}) so the module does not bloat satellite processes such as {@code :push},
 * {@code :pushservice}, {@code :gameservice}, or dedicated anti-cheat processes.</p>
 *
 * <p>system_server 进程名是 {@code "system"}（包名 android），没有 Application.attach 生命周期，
 * 必须在 {@link #onPackageLoaded} 直接走 system-server 初始化路径。</p>
 */
public final class ModuleEntry extends XposedModule {
    private volatile String processName;
    private volatile boolean applicationAttachHookInstalled;
    private volatile boolean applicationContextReady;
    private volatile boolean systemServerReady;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            processName = param.getProcessName();
            if (TemplateConfig.VERBOSE_LOGS) {
                log(Log.INFO, TemplateConfig.LOG_TAG, "Loaded in process=" + processName
                        + ", framework=" + getFrameworkName()
                        + " " + getFrameworkVersion()
                        + ", api=" + getApiVersion());
            }
        } catch (Throwable t) {
            if (TemplateConfig.VERBOSE_LOGS) Log.e(TemplateConfig.LOG_TAG, "onModuleLoaded log failed", t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        preparePackage(param, "onPackageLoaded");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        preparePackage(param, "onPackageReady");
    }

    private void preparePackage(PackageLoadedParam param, String phase) {
        String pkg = param.getPackageName();
        String proc = processName != null ? processName : pkg;
        if (!TemplateConfig.shouldHookProcess(pkg, proc)) return;
        if (TemplateConfig.VERBOSE_LOGS) {
            log(Log.INFO, TemplateConfig.LOG_TAG, phase + " " + pkg
                    + " process=" + proc
                    + " firstPackage=" + param.isFirstPackage()
                    + " dataDir=" + param.getApplicationInfo().dataDir);
        }

        // system_server（进程名 "system"，包名 android）没有 Application.attach 生命周期，
        // 直接走系统服务初始化路径（只读诊断 Hook）。
        if ("system".equals(proc)) {
            initSystemServer();
            return;
        }

        installApplicationAttachHook();
    }

    /** system_server 专用初始化：安装只读诊断 Hook（不需要 Context/Application）。 */
    private synchronized void initSystemServer() {
        if (systemServerReady) return;
        systemServerReady = true;
        try {
            if (TemplateConfig.VERBOSE_LOGS) {
                log(Log.INFO, TemplateConfig.LOG_TAG, "Initializing system_server diagnostics");
            }
            Bootstrap.startForSystemServer(this);
        } catch (Throwable t) {
            if (TemplateConfig.VERBOSE_LOGS) {
                log(Log.ERROR, TemplateConfig.LOG_TAG, "system_server init failed", t);
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi") // Xposed needs this stable pre-Activity context handoff.
    private synchronized void installApplicationAttachHook() {
        if (applicationAttachHookInstalled) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object thiz = chain.getThisObject();
                            Context ctx = thiz instanceof Context ? (Context) thiz : null;
                            if (ctx == null && !chain.getArgs().isEmpty() && chain.getArg(0) instanceof Context) {
                                ctx = (Context) chain.getArg(0);
                            }
                            if (ctx != null) onApplicationContextReady(ctx);
                        } catch (Throwable t) {
                            if (TemplateConfig.VERBOSE_LOGS) {
                                log(Log.ERROR, TemplateConfig.LOG_TAG, "Application.attach post-hook failed", t);
                            }
                        }
                        return result;
                    });
            // A failure in onPackageLoaded must leave onPackageReady able to retry.
            applicationAttachHookInstalled = true;
            if (TemplateConfig.VERBOSE_LOGS) {
                log(Log.INFO, TemplateConfig.LOG_TAG, "Hooked Application.attach");
            }
        } catch (Throwable t) {
            if (TemplateConfig.VERBOSE_LOGS) {
                log(Log.ERROR, TemplateConfig.LOG_TAG, "Could not hook Application.attach", t);
            }
        }
    }

    private synchronized void onApplicationContextReady(Context context) {
        if (applicationContextReady) return;
        applicationContextReady = true;
        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        // Idempotent via Bootstrap's static guard. ModuleEntry (XposedModule) 本身即 api。
        Bootstrap.start(appContext, this);
    }

}