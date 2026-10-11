# 阶段 4：可验证实现方案设计

> 基于阶段 2/3 的**本机反编译证据**（`docs/HOOK_POINTS.md`、`docs/ARCHITECTURE.md`、`docs/SESSION_MODEL.md`）。
> 此方案是阶段 5–7 的施工蓝图，执行前需用户批准。

## 综述

ColorOS 15 的 MediaProjection 链路存在**两层约束**：
1. **MediaProjectionManagerService（服务端）单槽字段**：普通 App 轨只有一个 `mProjectionGrant` slot，新启动顶掉旧——**纯 Java Hook 可改造为多会话表**。
2. **ContentRecordingController（WMS）全局单会话** + **ContentRecorder 每 Display 单录制 Surface**——这是 Java/Dex 层可控范围之外的**图形链路单会话设计**。

因此方案分为**互不混淆的两条路径**（对应需求第 4 条的阶段 6/7 拆分）：

### 路径一：范围选择 Hook（阶段 6，SystemUI 作用域，低风险）
让用户在授权弹窗中选择"整个屏幕 / 单个应用"，即使请求方配置了"固定整屏"，也不禁用单应用选项。

### 路径二：多会话系统服务改造（阶段 7，system 作用域，高风险）
在 server 端建立会话注册表，实现多 App 独立 Token/生命周期。**同时如实评估画面并行输出的限制**。

---

## 路径一：SystemUI 范围选择（阶段 6）

### 目标
需求 3：所有第三方 App 保留自己的授权弹窗，用户每次可自行选择"整个屏幕 / 单个应用"，即使请求方配置默认限制为整屏也开放两种选择。不能自动点击或绕过用户同意。

### Hook 点（✅ 本机确认）
`MediaProjectionPermissionDialogDelegate.Companion.createOptionList(Context, String, MediaProjectionConfig, boolean)`
- 原逻辑：`config.getRegionToCapture()==1` 且 `appName!=null` 且非 forceShow → 单应用选项设 `spinnerDisabledText`（非 null）+ 列表 `reversed()`。
- **Hook 后**：当检测到单应用选项 `spinnerDisabledText != null` 时，构造一个 `spinnerDisabledText = null` 的新 `ScreenShareOption` 替换之，并**不反转列表**（保持 单应用/整屏 正常顺序）。这样用户可自由选择，且选择结果经 `getSelectedScreenShareOption().getMode()` 正确传入 `grantMediaProjectionPermission`。

### 交互闭环（本机确认）
1. `grantMediaProjectionPermission(mode)`：
   - `mode==1` → `createOrReuseProjection` 整屏授权 → finish result。
   - `mode==0` → `createOrReuseProjection` + 启动 `MediaProjectionAppSelectorActivity`（Task Picker）→ 用户选任务 → `setContentRecordingSession(createTaskSession(...))`。
2. 取消/拒绝 → `finish(0)`，不产生投影。
3. 旋转/进程死亡 → Activity 重建（onCreate 重新读 intent），无泄漏（dialog 随 activity 销毁）。

### 验证标准
- 请求方 `createScreenCaptureIntent(CAPTURE_REGION_FIXED_DISPLAY)` 时，弹窗仍显示"单个应用"可选。
- 选"单个应用"→ Task Picker → 录制目标确为该 App 窗口。
- 选"整个屏幕"→ 整屏录制。
- 取消 → 无投影、无 crash。
- 黑名单（pubgmhd/sgame）行为不变（尊重厂商强制整屏，除非用户要求额外放开，需单独批准）。

### 独立性开关
- `FeatureRegistry.KEY_RANGE_CHOICE`（`range_choice`），默认关，开启后在 SystemUI 生效。

---

## 路径二：多会话系统服务改造（阶段 7，高风险）

### 目标
需求 4/5/6：多 App 独立授权 Token、录制目标、VirtualDisplay/Surface、Callback、停止生命周期。启动 B 不停 A；停止 B 不停 A/C。各 App 可选不同目标。

### 会话注册表设计（替代单槽）
在 `MediaProjectionManagerService` 内部维护：
```java
// 新增（Hook 字段注入，不删原字段以避免兼容性问题）
Map<IBinder, MediaProjection> mMultiProjections = new ArrayMap<>();  // token → projection
```

每个会话（MediaProjection）天然独立（visited 已验证其字段）：
- `uid/packageName/mType/mSession/mVirtualDisplayId/mTaskId/mLaunchCookie/mDeathEater/mToken/mCountStarts/mCreateTimeMs/mTimeoutMs`

### 逐方法替换策略
| 原方法 | Hook 后行为 | 对应需求 |
|---|---|---|
| `startProjectionLocked(projection)` | 不再 stop 旧会话；`mMultiProjections.put(projection.asBinder(), projection)`；为**兼容** ISystemUI/screenrecorder 仍维护 `mProjectionGrant`/`mOplusProjectionGrant` 指向（若存在） | 4 |
| `stopProjectionLocked(projection)` | 只 `mMultiProjections.remove(projection.asBinder())`；dispatchStop 仅通知该会话回调 | 4 |
| `isCurrentProjection(token)` | `mMultiProjections.containsKey(token)`（**等价于原语义**，非恒真） | 6 |
| `getProjectionInternal` | 查 Map；保留一次性 Token 规则（`mSession.isWaitingForConsent()` 且匹配） | 6 |
| `setContentRecordingSession(session, projection)` | 校验 token 在表中 → 写到该会话 `mSession` → 调 WMS | 4/5 |
| `stopActiveProjection()` | 仍停"当前激活"（双轨指向），或按调用方 token 过滤；不误停其他会话 | 6 |
| `getActiveProjectionInfo()` | 若存在多会话，返回调用方/最近启动的（**兼容单值 API**） | 4 |
| CallbackDelegate | 每个媒体投影回调只发给**自己**的 callback（在 `MediaProjection` 上记录会话回调集） | 4 |
| `handleForegroundServicesChanged` | 只停 FGS-uid 匹配的会话 | 7 |
| `MediaRouterCallback.onRouteSelected` | 停所有会话（与现状一致，避免 remote display 混流） | 7 |
| `dump()` | 遍历 `mMultiProjections` | 诊断 |

### 锁与线程模型
- 全部操作在现有 `synchronized (mLock)` 内进行，不做跨锁。
- 会话表用 `ArrayMap`（system_server 内存友好），避免 hot path 大对象分配。
- Binder death / FGS / 路由回调均复用现有 Handler/线程（`Looper.getMainLooper()` / `createCallbackLooper()`）。

### 与 WindowManager 的多会话映射（如实评估）
- **ContentRecordingController 全局单 `mSession`**（✅ 已确认 `jadx/services/.../ContentRecordingController.java`）
- **ContentRecorder 每 Display 单 `mRecordedSurface`**（✅ 已确认 `jadx/services/.../ContentRecorder.java`）

**因此阶段 7 的可验证边界**：
1. **Token/生命周期多会话**：✅ Java Hook 可控，可测（多 App 各自 start/stop 互不影响）。
2. **多路画面并行**：受 WMS+SurfaceFlinger 单会话约束，Java Hook **无法保证**同一 Display 多路镜像。
   - 替代路径：每会话独立 VirtualDisplay（display id 不同）→ 各 VD 有自己的 DisplayContent/ContentRecorder。
   - 但 `ContentRecordingController.setContentRecordingSessionLocked` 仍会覆盖旧 session → **需要在该层做多 session 表替换**（Hook `setContentRecordingSessionLocked`，改为按 displayId 存 Map）。
   - **必须真机实测**：同一真实 Display 是否可被多个 VD 同时镜像（SurfaceFlinger 能力）。
3. 若实测确认 Native 层不支持 → 停止危险尝试，**如实输出"仅生命周期多会话 + 画面受限于单镜像"的结论**，不做伪实现。

### 失败回滚与恢复
- 所有 Hook 异常捕获（`ExceptionMode.PROTECTIVE`），异常不影响原调用。
- 模块关闭/移除 → system_server 下次重启恢复原逻辑（无持久修改）。
- 提供 `RISKS_AND_RECOVERY.md` 中的禁用/恢复流程（阶段 9 交付）。

### 性能预算
- 会话查表 O(1)；不阻塞热路径；日志限流（每会话 start/stop 各 1 条）。
- 不打印 Token 可重用细节（只记 uid/packageName/virtualDisplayId）。