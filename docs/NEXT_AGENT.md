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

## 阶段6b 二次实测：ctx=true 但 rule=false → 规则读取路径修复（2026-10-12 21:35）

### 现象（第二次重启后）
- 诊断日志：`hasProjectionPermission check: uid=10343 pkg=com.zmzx.college.search ctx=true rule=false`
- **ctx=true**（上轮 Context 修复已生效），但 **rule=false**（规则仍未读到）

### 根因
- 重启后模块 App 处于 stopped/未运行状态（`dumpsys package` 显示 User 0 stopped=true、notLaunched=false）
- ContentProvider 无法自动拉起 stopped 的 App → Provider 查询失败 → 规则读不到

### 修复（commit d1408cd）
- **RuleStore 主路径改为直读 `/data/misc/mpfw/rules.json`**（不再依赖 App 进程存活）
  - 已验证：`su system -c cat /data/misc/mpfw/rules.json` 成功（uid=1000 可读）
  - 目录 755 + 文件 644，root 写入
- RulesConfig.syncToSystemPath：配置页保存时经 `su -c` 同步规则到 /data/misc/mpfw
- RulesActivity 增删规则统一走 persistRules（私有保存 + 系统路径同步）
- RuleProviderHelper 封装 ContentProvider 兜底查询（仅当 /data/misc 副本缺失时）

### 关键事实（勿重踩）
1. system_server uid=1000 **不是 root**，读不了 App 私有文件（0600）
2. 重启后模块 App 会进入 stopped 状态，ContentProvider 无法自动拉起 → Provider 桥在重启后不可靠
3. **/data/misc/mpfw/**（755 + 644）是 system_server 可读、重启保留、root 可写的可靠通道
4. 配置页经 su(root) 写 /data/misc —— 需 KernelSU 授权模块 App 的 su；若未授权则静默失败（Provider 兜底）

### 待用户重启验证
- 预期：`auto-grant hit: uid=10343 pkg=com.zmzx.college.search mode=full_screen ctx=true`
- 新 APK 已装（21:37），system_server 需重启加载新 dex

## 阶段6b 三次实测：免弹窗生效但投影响无效+高频重试 → 诊断版（2026-10-12 22:00）

### 现象（第三次重启后）
- ✅ auto-grant hit 生效（不弹窗）
- ❌ 搜题酱投影无效（media_projection=null，无活动投影）+ 状态栏闪烁
- 日志：21:43:21-35 每 1-2 秒一次 `createProjectionInternal enter uid=10343 pkg=com.zmzx.college.search`（高频重试）

### 已确认的关键机制（反编译）
1. framework `MediaProjection.getMediaProjection(code, data)` 纯取 intent 的 EXTRA_MEDIA_PROJECTION binder，**不调服务端 getProjection**
2. framework `MediaProjection` 构造函数**自动调 `mImpl.start(callback)`**（MediaProjection.java 36行）
3. 服务端 `MediaProjection.start()`（837-855行）：ColorOS `checkPermission("capture_or_mirror_screen")` 返回值**被忽略**；真正抛异常的是 `requiresForegroundService() && !hasFGS` → SecurityException（854行）
4. `requiresForegroundService() = targetSdk>=29 && !isPrivileged`（1023-1025行）——搜题酱满足，需 FGS type=32
5. `isValid()`（982-1003）：超时/mCountStarts>1/已建VD → false → 可能抛 SecurityException

### 当前诊断版（commit ab7d2c8）
- 加 `MediaProjection.start` hook：记录 `projection.start OK` 或 `projection.start EX: ... #异常`
- 加 `MediaProjection.stop` hook：记录 `projection.stop`
- 保留 getProjectionInternal 缓存兜底（uid+pkg→projection，TTL 30s）
- 已部署设备（22:00）

### 待用户重启验证（这次重启应有决定性答案）
看日志判断：
- `projection.start OK` → start 成功，问题在后续 VD 创建/isValid
- `projection.start EX: java.lang.SecurityException` → FGS 或复用检查失败
- 无 start 日志 → App 在 getMediaProjection 前就失败（onActivityResult 异常）

## 阶段6b 四次实测：循环根因锁定为 getProjection 缓存兜底（2026-10-12 23:00）

### 现象（第四次重启后，跑 session注入版 22:37 装的）
- ✅ injected content session: mode=full_screen 生效
- ❌ 循环依旧：auto-grant hit -> start OK -> stop 每 0.5-1s 一次
- 用户反馈：闪烁几下后正常可截图，但只能退到桌面用

### 根因（决定性分析）
- SystemUI 免弹窗分支 createOrReuseProjection(uid,pkg,consent_required=true)：
  `projection = z ? getProjection(uid,pkg) : null; return projection==null ? createProjection(...) : projection;`
- 诊断版(ab7d2c8)加的 getProjection 缓存兜底：返回缓存的上一个投影（已 start/已建VD）
- 搜题酱复用旧投影 -> isValid() 失败(mCountStarts>1/已建VD) -> SecurityException -> 重试 -> 循环
- 闪烁几下后正常 = 缓存偶尔过期(TTL 30s)才新建成功

### 修复（commit 65100bf，23:06 已部署）
- 完全移除 getProjection hook + 投影缓存（ProjectionEntry/PROJECTION_CACHE 等）
- 恢复原生语义：getProjection 返回 null（注入session waitingForConsent=false 不满足复用）
  -> 每次都 createProjection 新建 -> 等价正常弹窗路径
- 保留 ContentRecordingSession 注入（整屏 createDisplaySession(0) / 单应用 createTaskSession）

### 待重启验证
- 预期：不再循环、一次成功、无闪烁
- 若仍循环：需查 WMS setContentRecordingSession 是否失败（失败会 stop 投影 315行）

## 阶段6b 五次实测：根因 = AppOps OP_PROJECT_MEDIA 未授予（2026-10-13 09:55）

### 现象（跑移除缓存+session注入版 09:51 重启后）
- stop caller 决定性日志：`at ...MediaProjection.$r8$lambda$pSaMeYhcLhUPzKCyAZ6BcuCIr3E` = binderDied 回调
- vdId 递增（4,5,...12）说明 VD 创建成功，但投影随即 stop
- 搜题酱进程 pid 从 29741 变 369（闪退/被杀重启）-> callback binder 死亡 -> stop
- AppOps：`PROJECT_MEDIA: ignore; rejectTime=+4m4s ago` —— 从未被授予！
- 用户反馈：搜题酱界面内点悬浮窗 -> 通知栏闪烁 -> 回桌面才能截图；再点 -> 闪退

### 根因（最终确认）
- hasProjectionPermission 原逻辑 = checkPermission(CAPTURE_VIDEO_OUTPUT) || AppOps.noteOp(46)==0
- 我们 hook 返回 true 只骗过 SystemUI 免弹窗分支（98-111行）
- 但服务端真实授权状态（AppOps OP_PROJECT_MEDIA=46）未写入 -> 投影授权语义不完整
- -> VD 创建/录制异常 -> 搜题酱判定失败反复重试 -> 最终闪退
- 正常弹窗授权会 mAppOps.setMode(46, uid, pkg, 0)（createProjectionInternal 452行，isPermanentGrant=true）

### 修复（commit 52d7103，已部署 23:1x）
- createProjectionInternal 命中规则时同步 grantProjectMediaAppOp：
  `AppOpsManager.setMode(int=46, int uid, String pkg, int=0 MODE_ALLOWED)`
- 一次写入持久授权，之后 hasProjectionPermission 走原生逻辑即返回 true
- 保留 ContentRecordingSession 注入（整屏/单应用）+ 移除缓存兜底

### 待重启验证
- 预期：搜题酱首次授权后 AppOps 46=allow，投影一次成功、不闪烁、不闪退
- 若仍有问题：查 AppOps 授予是否成功（日志 granted PROJECT_MEDIA AppOps）

## 阶段6c A/B 基线测试：停用 MPFw 模块（2026-10-14 08:10）

### 关键判别数据（第六次重启，07:34 启动 system_server）
- AppOps PROJECT_MEDIA：重启后仍 allow（22:33 手动授予持久化，+3m53s ago）
- **但循环依旧**（vdId 29->33 连续递增）=> AppOps 不是唯一根因！
- grantProjectMediaAppOp failed: InvocationTargetException
  cause=SecurityException: uid 10216 does not have android.permission.MANAGE_APP_OPS_MODES
  => hook 回调里 Binder.getCallingUid() 泄漏为 SystemUI(10216)，setMode 被拒
- 修复（commit 21c6928 已部署 08:06）：setMode 前 Binder.clearCallingIdentity

### 循环机制（重估）
- 每次 auto-grant hit -> 注入 session -> start OK -> 0.1-0.5s 后 stop -> 再 auto-grant
- stop caller 两种：搜题酱主动(execTransactInternal) + 服务端顶替(HookBridge)
- vdId 连续递增：每次新建 VD（notifyVirtualDisplayCreated 成功调 setContentRecordingSession）
- 用户：搜题酱界面内点悬浮窗->通知栏闪烁->回桌面才能截图；再点->闪退
- 用户：搜题酱开启隐屏模块 ac.no.screenshot（scope 确认 hook 搜题酱）

### 疑点："回桌面才能截图"
- 可能 ColorOS 整屏录制不允许录自己前台？或隐屏模块影响前台判定？
- 用户说传统手动授权不冲突（可用）=> 原始授权流程正常

### 当前状态：A/B 基线测试进行中
- 已备份 modules_config.db -> .bak_autogrant
- 已停用 com.fairyxh.multiprojection（modules_state enabled=0）
- 待重启：让 LSPosed 卸载模块注入，搜题酱走原始授权流程
- 若原始流程正常 => MPFw hook 引入问题；若也循环 => 搜题酱+隐屏+ColorOS 本身

## 阶段6d 隐屏模块 ac.no.screenshot 确定为最大嫌疑（2026-10-14 08:44）

### 决定性实验（MPFw 停用 + AppOps default + 隐屏启用）
- 用户重启后：手动点授权弹窗 -> 状态栏闪一下 -> 立即弹第二个弹窗 -> 无限循环
- **纯原生手动授权流程也循环** => 彻底排除 MPFw hook 引入
- 隐屏模块 ac.no.screenshot（一直启用，hook 搜题酱）成最大嫌疑

### 隐屏模块机制（classes.dex strings 实证）
- hook WindowManagerGlobal.addView / ViewRootImpl.setView / relayoutWindow
- hook SurfaceControl.Transaction / findValidSurface
- 字段：mSetSkipScreenshot / mSetSkipScreenshotLegacy / setSkipScreenshot
- mSurface / mSurfaceControl / mSurfaceControlLocked / fSurface / fView / fWindowAttributes
- 作用：搜题酱进程内拦截 addView，把匹配 protectedViews 的窗口标 setSkipScreenshot
  -> 录屏时隐藏目标窗口
- 这些 hook 点正是 MediaProjection 录屏管线 => 极可能破坏授权/录屏流程

### 已执行：停用隐屏模块
- 备份 db -> .bak_hidescreen
- ac.no.screenshot enabled=0（MPFw 保持停用，AppOps default）
- 待重启：搜题酱走完全纯净原生手动授权
- 若不再循环 => 隐屏模块是根因；用户需在隐屏模块里排除搜题酱或改用其他隐屏方案

## [2026-10-14 11:15] UI 重构完成（iOS 风格 + 底栏）

- **已提交**：`2b3d8e8`（feat: 重设计模块 App 页面为 iOS 风格 + 底栏 4 Tab）
- **新 APK 已部署**：`MPFw-ui.apk`（pm install -r -d 成功，含全部 6 个新 UI 类）
- **UI 无需重启即可验证**：直接打开 MPFw App（LauncherActivity）即可看到新界面
- **构建关键经验（重要）**：aapt2 daemon 启动失败不是内存问题，而是 proot 下直接 exec x86_64 aapt2 会 SIGILL。
  必须使用 `/opt/aapt2wrap/aapt2`（qemu-amd64-static wrapper）。gradle.properties 中：
  `android.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2`
- **新 UI 结构**：ui/iOSStyle.java + HomeTab/RulesTab/PrinciplesTab/AboutTab + LauncherActivity（4 Tab 底栏）
- **已删除**：旧 RulesActivity.java（Manifest 已同步移除）
- **已验证**：APK classes4.dex 含全部新类，构建成功，安装成功

### 待办
- 用户验证新 UI（打开 MPFw App 看首页/规则/原理/关于 4 Tab）
- 确认规则增删改功能正常（RulesTab 复用 RulesConfig 数据层）
