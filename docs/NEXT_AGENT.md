# NEXT_AGENT.md — Agent 接力文档

> 新会话/新 Agent 先读本文件 + `docs/PROGRESS.md` + `docs/HOOK_POINTS.md` + `docs/ARCHITECTURE.md`，再动代码。

## 项目一句话
LSPosed 模块，包名 `com.fairyxh.multiprojection`，在 Android 15 / ColorOS 15 上实现多 App 独立 MediaProjection 会话（仅 system + com.android.systemui 作用域）。

## 仓库与构建
- 位置：`/home/projects/MultiProjectionFramework`（git，远程 origin 指向 Jordan231111 模板，本地已改包名/纯 Java）
- 构建（必须带 aapt2 wrapper）：
```bash
cd /home/projects/MultiProjectionFramework
source /opt/android-env.sh
export GRADLE_USER_HOME=/opt/gradle-home
/opt/gradle-9.3.1/bin/gradle :app:assembleDebug \
  --console=plain --no-daemon --init-script /opt/gradle-init.gradle \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2
```
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- **⚠️ 内存注意**：宿主 aarch64 proot，可用内存常 <2GB。jadx 大任务会 OOM → 使用 `--single-class` 单类反编译 + `JAVA_OPTS='-Xmx1536m'`。绝不同时跑多个 jadx。

## 当前进度
- ✅ 阶段 0（环境+纯Java化+可构建）
- ✅ 阶段 1（提取 5 个系统文件）
- ✅ 阶段 2/3（关键类已反编译 + 架构/会话模型分析）
- ✅ 阶段 4（设计文档 DESIGN_IMPLEMENTATION.md 已出，**待用户批准后进入阶段 5**）
- ⏳ 阶段 5（只读诊断模块）待编码

## 已确认关键结论（勿重复研究）
1. ColorOS MediaProjectionManagerService 是"双轨单槽"：`mProjectionGrant` + `mOplusProjectionGrant`，新 App 顶掉旧 App。
2. `ContentRecordingController` 全局单 `mSession`，`ContentRecorder` 每 Display 单 Surface → **多路画面并行受 WMS/SurfaceFlinger 限制**，Token/生命周期多会话可纯 Java 实现。
3. SystemUI 授权弹窗**原生已有**"整屏/单应用" Spinner；`createOptionList` 在 `CAPTURE_REGION_FIXED_DISPLAY` 时禁用单应用选项 → 阶段 6 Hook 点。
4. `MediaProjectionPermissionActivity.grantMediaProjectionPermission(mode)`：mode=1 整屏、mode=0 走 AppSelector。
5. LSPosed 运行中（lspd 进程），Manager 2.1.0-it；SystemUI 在 /system_ext/priv-app。

## 关键文件索引
| 文件 | 说明 |
|---|---|
| `docs/HOOK_POINTS.md` | 所有已确认 Hook 点（方法签名、行为） |
| `docs/ARCHITECTURE.md` | 端到端架构 + 多会话限制证据 |
| `docs/SESSION_MODEL.md` | 会话模型设计 |
| `docs/DESIGN_IMPLEMENTATION.md` | 阶段 6/7 实施方案（待批准） |
| `docs/ENVIRONMENT.md` | 构建/工具链 |
| `docs/EXTRACTION.md` | 提取清单 |
| `docs/PROGRESS.md` | 进度表 |
| `jadx/single/.../MediaProjectionManagerService.java` | **核心服务端源码（1272行完整）** |
| `jadx/systemui/.../*.java` | SystemUI 授权链 |
| `jadx/framework/.../ContentRecordingSession.java` | 会话描述对象 |
| `jadx/services/.../ContentRecordingController.java` | WMS 单会话控制器 |
| `jadx/services/.../ContentRecorder.java` | 每 Display 单录制器 |

## 下一步（等待用户批准后执行）
1. ✅ **阶段 5**：诊断模块已编码+构建+安装+启用（见下）
2. ⏳ **等待用户手动重启设备** → system_server 注入生效
3. 重启后验证：`logcat -s MPFw` 应见 `[Diag] server diagnostics installed` 及 MediaProjection 方法日志
4. 调起任意 App 录屏 → 观察 startProjectionLocked/stopProjectionLocked/setContentRecordingSession 日志
5. 阶段 6：SystemUI 范围选择 Hook（createOptionList 解除禁用）

## 阶段 5 部署状态（2026-10-11 21:06 更新）✅ **阶段 5 完成**
- ✅ system_server 注入 → `onSystemServerStarting` → `Diagnostics.installForSystemServer` → **7 个 Hook 全部安装成功**
- ✅ 实测录屏（21:04，com.oplus.screenrecorder）：`createProjectionInternal enter uid=10124 pkg=com.oplus.screenrecorder type=0 svc=MediaProjectionManagerService`
- ✅ `handleForegroundServicesChanged` 持续捕获（pid/uid 正常）
- 🔬 **关键教训（勿重踩）**：
  1. system_server 中 `android.util.Log` **不可观测** → 必须用 `XposedInterface.log()`（走 LSPosed log daemon）
  2. `Class.forName` 默认 classloader 找不到 services.jar 内部类 → 必须用 `SystemServerStartingParam.getClassLoader()`
  3. `onPackageLoaded` 在 system_server **不触发**，正确入口是 `onSystemServerStarting`
- 📌 日志查看命令：`grep -a 'multiprojection' /data/adb/lspd/log/modules_*.log | tail -25`
- 当前模块在 system + com.android.systemui + android 作用域，LSPosed 启用

## 阶段 6（SystemUI 范围选择 Hook）— 编码中
- Hook 点：`MediaProjectionPermissionDialogDelegate$Companion.createOptionList(Context, String, MediaProjectionConfig, boolean)` ✅ 已反编译确认
- 实现文件：`app/src/main/java/com/fairyxh/multiprojection/RangeChoiceHook.java`（新建）
- 策略：保持列表顺序/默认选中不变（FIXED_DISPLAY 时整屏在前为默认），仅把 `mode==0`（单应用）的 `spinnerDisabledText` 清为 null → 解除禁用，用户可切"单个应用"
- 接入：`Bootstrap.start()` 中 `KEY_RANGE_CHOICE` && `isSystemUiPackage` 时调用 `RangeChoiceHook.install`
- 独立性开关：`TemplateConfig.RANGE_CHOICE_ENHANCED`（默认 false）+ `FeatureRegistry.KEY_RANGE_CHOICE`
- ⚠️ **图标黑名单注意**：pubgmhd/sgame 黑名单强制整屏逻辑在 `getMediaProjectionConfig()`（早于此 Hook），本 Hook 无法按包名区分 → 对黑名单也会放开单应用。如需对黑名单保持强制，需额外按包名负责（待批准）
- ⚠️ 验证方式：需开启 `range_choice` 开关 + 重启 SystemUI 进程；由 `MediaProjectionPermissionActivity` FIXED_DISPLAY 请求方触发

## 阶段 6 部署状态（2026-10-12 15:05 更新）
- ✅ `RangeChoiceHook` 已成功安装到 SystemUI（15:04 重启 SystemUI 进程验证）：
  `[RangeChoice] RangeChoiceHook installed: ...MediaProjectionPermissionDialogDelegate$Companion#createOptionList`
- 🔧 **验证期默认开启** `RANGE_CHOICE_ENHANCED=true`（commit ace2fa0），避免依赖悬浮菜单手动开关
- ⏳ 已加运行时日志（记录 appName/region/forceShow + 结果），待重新构建安装后触发一次 MediaProjection 授权弹窗验证"单个应用"可选
- ⚠️ SystemUI 进程可单独 `pkill -f com.android.systemui` 重启（无需整机重启）；模块更新后需重装 APK + 重启 SystemUI 进程
- 验证命令：`grep -a 'RangeChoice' /data/adb/lspd/log/modules_*.log | tail -10`

## 风险提醒
- 阶段 5/7 启用 system 作用域前必须获用户批准 + 说明 bootloop 风险。
- 任何重启由用户手动执行，Agent 不 reboot。
- 大文件（extracted/、jadx/、logs/、builds/）已 gitignore，不入库。
## 阶段 6b（自动授权不弹窗）— 2026-10-12 编码完成，待重启验证

### 需求与方案
- 需求：规则内 App 发起 MediaProjection 时**自动授权、完全不弹窗**；根治"频繁掉授权"（logcat 实证：com.zmzx.college.search 每几秒触发一次 createProjection）。
- 核心机制（反编译确认）：SystemUI `MediaProjectionPermissionActivity.onCreate()` 中 `hasProjectionPermission(uid,pkg)==true` → 直接 `createOrReuseProjection` + `setResult(-1)` + `finish()`，**完全不弹窗**。
- 实现：hook system_server `MediaProjectionManagerService$BinderService.hasProjectionPermission(int,String)` 命中规则返回 true。

### 新增文件
| 文件 | 作用 |
|---|---|
| `rule/RulesConfig.java` | 规则模型（appPkg + mode[整屏0/单应用自身1/单应用自定义2] + targetPkg） + JSON 存取 files/rules.json |
| `rule/RuleStore.java` | system_server 侧读规则（经 ContentProvider 桥，App 进程读文件返回 JSON） |
| `rule/RuleProvider.java` | ContentProvider：以 content://com.fairyxh.multiprojection.rule/rules 暴露 rules.json（system_server uid=1000 无法直接读 App 私有文件 0600，必须桥接） |
| `rule/AutoGrantHook.java` | system_server Hook：hasProjectionPermission→true；createProjectionInternal 返回后注入 task session（单应用） |
| `ui/RulesActivity.java` | 模块 App 配置页（新增/删除规则） |
| `LauncherActivity` 增加"自动授权规则"入口 |

### 单应用模式（task session 注入）
- `RunningTaskInfo/TaskInfo`（反编译确认）：`topActivity`/`baseActivity` 是 **ComponentName** 字段、`token` 是 **WindowContainerToken** 字段、`taskId` 是 int 字段。
- `WindowContainer.fromBinder(token)` = `RemoteToken.fromBinder(binder).getContainer()` → token 必须是绑定到目标 Task 的 RemoteToken binder。
- `ContentRecordingSession.createTaskSession(token, taskId)` 构造 task session → 反射写入 projection.mSession → App 建 VD 后 `notifyVirtualDisplayCreated` 自动推给 WMS。
- 注入点：`createProjectionInternal` 返回后反射写 mSession；task 查询用 `ActivityManager.getService().getTasks(100,true,false,-1)`。

### 部署状态
- ✅ 构建成功：`app/build/outputs/apk/debug/app-debug.apk`（2.5MB）
- ✅ 已安装到设备（pm install -r -d Success）
- ✅ RulesActivity 可正常打开（topResumedActivity 验证）
- ✅ **ContentProvider 桥实测通过**：`content query --uri content://com.fairyxh.multiprojection.rule/rules` 返回
  `[{"appPkg":"com.zmzx.college.search","mode":0},{"appPkg":"com.chaoxing.mobile","mode":1}]`
  （注意：force-stop 后 provider 不自动拉起，属正常 stopped 状态行为；system_server 首次调用会自动 bind 模块 App 进程）
- ✅ 已写入测试规则：`com.zmzx.college.search` mode=0（整屏） + `com.chaoxing.mobile` mode=1（单应用-自身）
- ⏳ **待重启 system_server 生效**（约定：重启由用户手动执行）

### 验证步骤（重启后）
1. `grep -a 'AutoGrant' /data/adb/lspd/log/modules_*.log | tail` → 应见 `AutoGrantHook installed`
2. 打开大学搜题酱（com.zmzx.college.search）触发录屏 → 应**不弹窗直接授权**，日志见 `auto-grant hit: uid=10343 pkg=com.zmzx.college.search mode=full_screen`
3. 未配置规则 App（如微信）录屏 → 仍正常弹窗

### 关键坑（勿重踩）
1. `android.app.ActivityThread` 是 @hide，公共 SDK 编译不过 → 必须反射（Bootstrap.resolveModuleDataDir 已用 Class.forName）
2. system_server 无 Application 生命周期，FeatureRegistry 未初始化 → AutoGrant 开关用 `TemplateConfig.AUTO_GRANT_ENABLED`（默认 true）而非 FeatureRegistry.getBool
3. `chain.getArgs().toArray()` 返回 Object[]，需判空

## 阶段6b 首次实测失败 → 根因与修复（2026-10-12 21:20）

### 现象
- 重启后 `AutoGrantHook installed` 成功（日志确认）
- 但大学搜题酱触发录屏时仍弹窗，无 `auto-grant hit` 日志

### 根因（已定位）
- `Bootstrap.getSystemServerContext()` 反射 `ActivityThread.currentActivityThread().getApplication()`
  在 system_server 中返回 **null**（system_server 没有 Application）
- → AutoGrantHook.sContext=null → `RuleStore.findRule(null, pkg)` 永不命中 → 不影响原逻辑但也不自动授权

### 修复（commit 5ce5456）
- **不再依赖 Bootstrap 传 Context**：hook 回调内从 `chain.getThisObject()`（BinderService 实例）
  反射取 `this$0` → 外层 `MediaProjectionManagerService` → 读 `mContext` 字段（反编译确认存在）
  或 `getContext()` 方法，成功后缓存到 sResolvedContext
- 加限频诊断日志（5秒窗口）：未命中时输出 `ctx= / rule=` 状态
- 注意：`XposedInterface.HookCallback` 不是公开类型不能作参数 → resolveContext 用 Object + 反射 getThisObject()

### 待用户重启验证
- 预期日志：`auto-grant hit: uid=10343 pkg=com.zmzx.college.search mode=full_screen ctx=true`
- 若未命中，看 `hasProjectionPermission check: uid=.. pkg=.. ctx=.. rule=..` 定位 ctx 或 rule 哪个环节断
