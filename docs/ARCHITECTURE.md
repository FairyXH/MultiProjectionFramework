# ARCHITECTURE.md — ColorOS 15 MediaProjection 端到端架构（本机反编译确认）

> 全部结论来自本机提取的反编译源码（`jadx/`），标注 `✅ 本机确认` / `🔶 AOSP 推测`。

## 0. 总体架构（ColorOS 定制）

```
第三方 App (createScreenCaptureIntent)
   │  SystemUI 授权弹窗（MediaProjectionPermissionActivity）
   ▼
MediaProjectionPermissionDialogDelegate（整屏/单应用 Spinner）
   ▼ grantMediaProjectionPermission(mode)
MediaProjectionServiceHelper.createOrReuseProjection(uid, pkg)
   ▼
MediaProjectionManagerService.BinderService.createProjection(uid, pkg, type=0)
   ▼ MediaProjection 对象（每 App 一个）
App.start(callback) → startProjectionLocked → mProjectionGrant 赋值
   ▼ App.createVirtualDisplay(surface)
notifyVirtualDisplayCreated(displayId) → ContentRecordingSession
   ▼
WindowManagerService.setContentRecordingSession → ContentRecordingController
   ▼
DisplayContent.setContentRecordingSession → ContentRecorder（每 Display 单实例）
   ▼ SurfaceFlinger（Native 层单 Surface 镜像）
```

## 1. 服务端 MediaProjectionManagerService（ColorOS 定制）

✅ 见 `jadx/single/.../MediaProjectionManagerService.java`
- **双轨但每轨单槽**：普通 App `mProjectionGrant` + 录屏 App `mOplusProjectionGrant`
- **新 App 顶掉旧 App**：`startProjectionLocked` 中 `if (mProjectionGrant != null && !pkg.equals("com.oplus.screenrecorder")) mProjectionGrant.stop();`
- `isCurrentProjection(token)`：只比较两个固定 token
- `getActiveProjectionInfo()`：返回 mProjectionGrant 或 mOplusProjectionGrant（单值 API）
- `stopActiveProjection()`：停两者（无 token 参数）
- `setContentRecordingSession(session)`：只作用于 `mProjectionGrant`（非 screenrecorder 轨）
- 一次性 Token：`getProjectionInternal` 只在 `mSession.isWaitingForConsent()` 时复用；`isValid()` 检查 `hasTimedOut || mCountStarts>1 || virtualDisplayCreated`，否则 stop + SecurityException

## 2. MediaProjection 对象生命周期（每 App 独立）

✅ MediaProjection 内部类
- 每个 `createProjection` 创建独立 `MediaProjection`（含 uid/pkg/mType/mSession/mVirtualDisplayId）
- `start(callback)`：校验 FGS → linkToDeath（Binder death → stop）→ startProjectionLocked
- `stop()`：isCurrentProjection 检查 → stopProjectionLocked → unlink → 回调
- 字段天然支持"多实例"，**瓶颈在 ManagerService 的单槽字段**，不在 MediaProjection 本身

## 3. ContentRecordingSession（录制目标描述）

✅ `jadx/framework/.../ContentRecordingSession.java`
- `RECORD_CONTENT_DISPLAY=0`（整屏，displayToRecord + targetUid=FULL_SCREEN(-1)）
- `RECORD_CONTENT_TASK=1`（单应用，tokenToRecord + taskId）
- `createDisplaySession(displayId)` / `createTaskSession(token, taskId)`
- `isValid()`：DISPLAY 需 displayToRecord>-1；TASK 需 tokenToRecord!=null
- `isProjectionOnSameDisplay(a, b)`：比较 virtualDisplayId

## 4. WindowManager 侧：ContentRecordingController（全局单会话 —— 关键限制）

✅ `jadx/services/.../ContentRecordingController.java`（仅 71 行）
- **全局单字段** `mSession` + `mDisplayContent`
- `setContentRecordingSessionLocked(incoming, wmService)`：
  - 同 display 新会话 → **忽略**（除非带 consent 更新）
  - 否则 → `mDisplayContent.setContentRecordingSession(incoming)` + `updateRecording()`
  - 并**覆盖旧 session**
- 结论：**整个 WMS 同时只有一个 ContentRecordingSession（单 display 单录制目标）**

## 5. WindowManager 侧：ContentRecorder（每 Display 单录制 Surface）

✅ `jadx/services/.../ContentRecorder.java`（577 行）
- 每 `DisplayContent` 一个 ContentRecorder，`mRecordedSurface` 单一 SurfaceControl
- `startRecordingIfNeeded()` → 通过 `SurfaceControl`/SurfaceFlinger 镜像录制窗口内容
- `stopRecording()` → remove surface
- **结论：Java 层每 Display 仅支持一个 ContentRecorder（一个镜像 Surface）**

## 6. 端到端调用链

### 链路 A：新投影启动
1. App `createScreenCaptureIntent(config)` → SystemUI `MediaProjectionPermissionActivity`
2. 用户确认（整屏 mode=1 / 单应用 mode=0 → AppSelector）
3. `MediaProjectionServiceHelper.createOrReuseProjection` → `BinderService.createProjection(uid, pkg, type=0, false)`
4. App `start(callback)` → `startProjectionLocked(this)`（**若已有 mProjectionGrant 则 stop 旧的**）
5. App `createVirtualDisplay(surface)` → `notifyVirtualDisplayCreated(displayId)`
6. 若已设置 session → `setContentRecordingSession(session)` → WMS 接管

### 链路 B：新投影顶掉旧的
- `startProjectionLocked` 第 195-198 行：新 App（非 screenrecorder）启动时若 `mProjectionGrant` 已存在 → `mProjectionGrant.stop()`
- `stop()` → `stopProjectionLocked` → 清空字段 → dispatchStop 给旧 App callback + watcher

### 链路 C：停止 / 崩溃 / FGS
- 正常停止：App `stop()` → isCurrentProjection → stopProjectionLocked → dispatchStop
- Binder death：`lambda$start$0` → unregisterCallback + stop()
- FGS 变化：`handleForegroundServicesChanged` → 若 uid 匹配且需 FGS 而 FGS 消失 → stop
- 路由变化：`MediaRouterCallback.onRouteSelected` remote display → stop 全部

### 链路 D：隐私提示与状态
- `getActiveProjectionInfo()` 单值；`dump()` 只 dump 当前投影
- SystemUI 状态栏投影指示依赖 watcher callback（dispatchStart/Stop）
- **所有 watcher/client callback 无会话区分**，遍历全部

## 7. 多会话改造的核心矛盾（证据）

| 层 | 当前设计 | 多会话需 | 可行性 |
|---|---|---|---|
| MediaProjectionManagerService | 双轨单槽 | 多槽（Map<Token, Projection>） | ✅ Java Hook 可改 |
| CallbackDelegate | 单一回调池 | 按投影过滤 | ✅ Java Hook 可改 |
| BinderService.isCurrentProjection | 两 token 比较 | Map 查询 | ✅ Java Hook 可改 |
| stopActiveProjection | 无 token 参数 | 需指定 token | ✅ Java Hook 可改 |
| **ContentRecordingController** | **全局单 mSession** | **多 display 多 session** | 🔶 需多 VirtualDisplay + 改造全局单会话 |
| **ContentRecorder** | **每 display 单 recorder** | **每 display 可多路输出** | ❌ 受 SurfaceFlinger 单 Surface 约束 |
| SurfaceFlinger | 单 SurfaceControl 镜像 | 多 Surface 并行镜像 | 🔶 依赖 Native 层能力 |

**结论（如实记录）**：
1. **Token/授权/生命周期多会话**：Java Hook 完全可控。
2. **多路画面并行捕获**：受 `ContentRecordingController`（全局单 session）+ `ContentRecorder`（每 display 单 surface）+ SurfaceFlinger 约束，**Java Hook 无法直接做到"同一 Display 多路镜像"**。
3. **可行替代**：每个会话绑定独立 VirtualDisplay（不同 display id），让 WMS 为每个 VD 建独立 DisplayContent/ContentRecorder——但 `ContentRecordingController.setContentRecordingSessionLocked` 仍是单会话覆盖语义，需要在该层做"多 session 表"替换，且需验证 SurfaceFlinger 是否允许同一真实 Display 内容被多个 VD 同时镜像（属 Native 层能力，需实测）。