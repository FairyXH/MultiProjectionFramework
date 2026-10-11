# HOOK_POINTS.md — 已从本机反编译确认的 Hook 点

> 标注：✅ 本机反编译确认 / 🔶 AOSP 推测 / ❌ 未发现
> 来源：`jadx/` 目录（services.jar → jadx/services、framework.jar → jadx/framework、SystemUI classes3.dex → jadx/systemui）

## 1. 服务端：MediaProjectionManagerService（ColorOS 定制版）

来源：`jadx/single/sources/com/android/server/media/projection/MediaProjectionManagerService.java` ✅

### 类结构
- `final class MediaProjectionManagerService extends SystemService implements Watchdog.Monitor`
- 关键字段：
  - `MediaProjection mProjectionGrant`（普通 App 当前投影，**单槽**）
  - `IBinder mProjectionToken`（普通 App 当前投影 token）
  - `MediaProjection mOplusProjectionGrant`（com.oplus.screenrecorder 专用轨）
  - `IBinder mOplusProjectionToken`
  - `Map<IBinder, IBinder.DeathRecipient> mDeathEaters`（watcher callback 死亡监听）
  - `CallbackDelegate mCallbackDelegate` / `mOplusCallbackDelegate`（分发器）
  - `IMediaProjectionManagerServiceExt mMediaProjectionManagerServiceExt`（Oplus 扩展，ExtLoader 创建）
  - `String OPLUSSCREENRECORDER = "com.oplus.screenrecorder"`

### 方法（签名与行为）
| 方法 | 签名 | 行为 | Hook 价值 |
|---|---|---|---|
| `startProjectionLocked` | `(MediaProjection) void` | 普通轨非 screenrecorder 时若已有 `mProjectionGrant` → 先 `stop()` 旧的再设新的（**新 App 顶掉旧 App**） | 🔴 多会话核心改造点 |
| `stopProjectionLocked` | `(MediaProjection) void` | 清空对应轨字段、dispatchStop | 多会话需按会话停止 |
| `isCurrentProjection` | `(IBinder) boolean` | 仅比较 `mProjectionToken` 与 `mOplusProjectionToken` | 🔴 单会话假设核心 |
| `setContentRecordingSession` | `(ContentRecordingSession) boolean` | 调 `mWmInternal.setContentRecordingSession`，只写 `mProjectionGrant.mSession` | 多会话需扩展 |
| `getActiveProjectionInfo` | `() MediaProjectionInfo` | 返回 `mProjectionGrant` 或 `mOplusProjectionGrant` | 单会话假设 |
| `createProjectionInternal` | `(int uid, String pkg, int type, boolean perm, UserHandle) MediaProjection` | 构造 MediaProjection | — |
| `getProjectionInternal` | `(int uid, String pkg) MediaProjection` | 仅当 `mProjectionGrant.mSession.isWaitingForConsent()` 且 uid/pkg 匹配才复用 | 一次性 Token 语义 |
| `handleForegroundServicesChanged` | `(int pid, int uid, int types) void` | FGS 变化时停止对应投影 | 需按会话处理 |
| `MediaRouterCallback.onRouteSelected` | — | 选中 remote display 路由时停所有投影 | 需按会话处理 |
| `dump` | `(PrintWriter) void` | 只 dump `mProjectionGrant` 或 `mOplusProjectionGrant` | 诊断可扩展 |

### MediaProjection 内部类（核心对象）
`final class MediaProjection extends IMediaProjection.Stub`
- 字段：`mCallback, mDeathEater, mSession, mToken, mType, packageName, uid, userHandle, mVirtualDisplayId=-1, mTaskId=-1, mLaunchCookie, mCountStarts, mCreateTimeMs, mTimeoutMs(5min默认)`
- `start(callback)`：检查 FGS → linkToDeath → `startProjectionLocked(this)` → `registerCallback`
- `stop()`：`isCurrentProjection` 检查 → `stopProjectionLocked` → unlink → unregisterCallback
- `binderDied()`（lambda）：`unregisterCallback(callback); stop()`
- `isValid()`：`hasTimedOut || mCountStarts>1 || virtualDisplayCreated` → 无效；且**若 `mProjectionGrant.packageName=="com.oplus.cast"` 则永远有效**；否则 stop + SecurityException（一次性 Token 语义）
- `notifyVirtualDisplayCreated(displayId)`：`mVirtualDisplayId=displayId`，若 `mSession` 存在则 `setContentRecordingSession`（把 VD id 写进 session）
- `registerCallback`：screenrecorder → `mOplusCallbackDelegate`，否则 `mCallbackDelegate`（**单一 callback 池**）

### CallbackDelegate
- `mClientCallbacks: Map<IBinder, IMediaProjectionCallback>`（App 的 onStop 回调）
- `mWatcherCallbacks: Map<IBinder, IMediaProjectionWatcherCallback>`（系统观察者）
- dispatchStart/Stop/Session/Resize/VisibilityChanged：遍历**全部** callback（无会话区分）→ 多会话需按投影过滤

### BinderService（对外 IPC）
- `createProjection(processUid, packageName, type, isPermanentGrant)`：需 MANAGE_MEDIA_PROJECTION
- `getProjection(processUid, packageName)`：getProjectionInternal
- `isCurrentProjection(projection)`：isCurrentProjection(token)
- `getActiveProjectionInfo()`：需权限
- `stopActiveProjection()`：停 mProjectionGrant + mOplusProjectionGrant（**无指定 token 参数**）
- `setContentRecordingSession(session, projection)`：**先检查 `isCurrentProjection(projection)`，否则抛 SecurityException**
- `setUserReviewGrantedConsentResult(consentResult, projection)`：只处理 mProjectionGrant

## 2. framework：MediaProjectionConfig

来源：`jadx/framework/sources/android/media/projection/MediaProjectionConfig.java` ✅
- `CAPTURE_REGION_FIXED_DISPLAY = 1`，`CAPTURE_REGION_USER_CHOICE = 0`
- `createConfigForDefaultDisplay()` → FIXED_DISPLAY（整屏）；`createConfigForUserChoice()` → USER_CHOICE
- 字段：`mDisplayToCapture, mRegionToCapture`

## 3. SystemUI：MediaProjectionPermissionActivity

来源：`jadx/systemui/.../MediaProjectionPermissionActivity.java` ✅
- `DEFAULT_DISPLAY_BLACK_LIST = ["com.tencent.tmgp.pubgmhd", "com.tencent.tmgp.sgame"]`（黑名单强制整屏）
- `getMediaProjectionConfig()`：黑名单 → `createConfigForDefaultDisplay()`；否则读 intent `EXTRA_MEDIA_PROJECTION_CONFIG`
- `isPartialScreenSharingEnabled()` → `Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING`
- 部分共享启用时 → `AlertDialogWithDelegate`（`MediaProjectionPermissionDialogDelegate`），否则普通 AlertDialog
- `grantMediaProjectionPermission(int i)`：`i==1` → 整屏授权；`isPartialScreenSharingEnabled() && i==0` → 启动 `MediaProjectionAppSelectorActivity`（单应用 Task Picker）
- `onClick(-1)` → `grantMediaProjectionPermission(1)`

## 4. SystemUI：MediaProjectionServiceHelper

来源：`jadx/systemui/.../MediaProjectionServiceHelper.java` ✅
- `service = IMediaProjectionManager.Stub.asInterface(ServiceManager.getService("media_projection"))`
- `createOrReuseProjection(uid, pkg, reviewRequired)`：reviewRequired → `getProjection`；否则 `createProjection(uid, pkg, type=0, false)`
- `setReviewedConsentIfNeeded(result, reviewRequired, projection)` → `setUserReviewGrantedConsentResult`

## 5. SystemUI：MediaProjectionPermissionDialogDelegate

来源：`jadx/systemui/.../MediaProjectionPermissionDialogDelegate.java` ✅
- `Companion.createOptionList(context, appName, config, forceShowPartial)`：
  - 生成两个 `ScreenShareOption`：`mode=0`（单应用）、`mode=1`（整屏）
  - 当 `config.getRegionToCapture()==1`（固定整屏）且 `appName!=null` 且非 forceShow → **单应用选项禁用 + 列表反转**（整屏排前）
- `onCreate`：绑定 start/cancel 按钮 → start 时 `onStartRecordingClicked.accept(this)`（即 `grantMediaProjectionPermission(getSelectedScreenShareOption().getMode())`）

## 6. SystemUI：BaseMediaProjectionPermissionDialogDelegate

来源：`jadx/systemui/.../BaseMediaProjectionPermissionDialogDelegate.java` ✅
- `screenShareOptions: List<ScreenShareOption>`、`defaultSelectedMode`、`selectedScreenShareOption`
- `initScreenShareOptions()`：按 `defaultSelectedMode` 选中对应 option、设置 warning 文案
- 实现 `DialogDelegate`、`OnItemSelectedListener`（Spinner 切换 option）

## 7. 阶段 6 关键 Hook 结论
**原版 SystemUI 已有"整屏/单应用"Spinner 选择**。唯一障碍是：
- `createOptionList` 中当请求方配置 `CAPTURE_REGION_FIXED_DISPLAY` 时**禁用单应用选项**（`spinnerDisabledText` 非空 + reversed）。

**Hook 策略（阶段 6）**：
- Hook `MediaProjectionPermissionDialogDelegate.Companion.createOptionList` 返回值：当检测到单应用选项被禁用（`spinnerDisabledText != null`）时，**清除该禁用态**（或不再反转），让用户可自由选择单应用/整屏。
- 或 Hook `MediaProjectionPermissionActivity.getMediaProjectionConfig()`：把 `CAPTURE_REGION_FIXED_DISPLAY` 改为 `USER_CHOICE`（需谨慎，可能影响合规语义，见 RISKS）。
- 目标：UI 选择与最终提交一致（`grantMediaProjectionPermission(selectedOption.mode)` → `createProjection(type=0)` + 启动 AppSelector 或整屏）。

## 8. 待补全（后续检索）
- `MediaProjectionAppSelectorActivity`（单应用 Task Picker 流程）
- `com.android.server.wm.ContentRecordingController` / `ContentRecorder`（WindowManager 侧录制会话）
- `ContentRecordingSession`（framework，RECORD_CONTENT_TASK / RECORD_CONTENT_DISPLAY 常量）
- Oplus/ColorOS 的 `IMediaProjectionManagerServiceExt` 实现
- `VirtualDisplayAdapter` / `DisplayManagerService` 多 VD 支持情况