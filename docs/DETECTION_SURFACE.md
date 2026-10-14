# DETECTION_SURFACE.md — 录制范围对 App 的检测面分析

> 问题来源：阶段 6 放开"禁用单应用选项"后，被禁单应用的 App（黑名单 pubgmhd/sgame 或
> 用 `createConfigForDefaultDisplay()` 请求的 App）被强制/允许走"单应用"时，能否察觉到自己
> 不是整屏录制？能否让它们以为自己是整屏？
>
> 本文档基于本机反编译证据（`docs/HOOK_POINTS.md` + `jadx/` 源码）回答。

## 结论（TL;DR）

**App 无法检测到自己开的是"单应用"而非"整屏"**，原因：
1. 整屏/单应用的选择**不通过任何 App 可见的通道返回**；
2. `MediaProjection` 对象的公开 API 面**不含录制范围信息**；
3. 真正记录范围的是服务端私有字段 `mSession`，App 无读取途径。

因此"让 App 以为自己开了整屏"**无需额外伪装**——单应用模式对 App 天然透明，
App 从一开始就"以为自己是整屏"。

---

## 一、App 侧可观测面逐一分析

### 1. 请求方自己的 `MediaProjectionConfig`（无法伪装的对象）

```java
// 禁单应用 App 的请求方式（自己决定的）：
createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()); // region=1 整屏
createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice());     // region=0 用户可选
```

- `regionToCapture` 是 **App 自己构造并写入 intent 的**，`SystemUI` 只是读取它决定 UI。
- 本模块 `RangeChoiceHook` 只改 `SystemUI` 弹窗的选项启用态，**不碰 App 的 intent/config**。
- **结论**：App 若检查"我请求的是整屏"，永远看到整屏——**这一层不会暴露**。

### 2. 授权返回的 `MediaProjection`（`IMediaProjection` binder）

整屏 / 单应用授权完成后，App 都拿到同一个 `MediaProjection` 对象（intent 里
`android.media.projection.extra.EXTRA_MEDIA_PROJECTION`）。区别仅在服务端内部：

| 授权结果 | 服务端 session | App 可见性 |
|---|---|---|
| 整屏（mode=1） | `createDisplaySession(0)` → `RECORD_CONTENT_DISPLAY` | ❌ 不可见 |
| 单应用（mode=0） | `setTaskId + setLaunchCookie` → `createTaskSession` → `RECORD_CONTENT_TASK` | ❌ 不可见 |

- `MediaProjectionManagerService$MediaProjection.mSession` 是 **private 字段**（反编译 782 行）。
- `IMediaProjection` binder 接口**没有**暴露"查询我的录制目标"的方法。
- `MediaProjectionInfo.getActiveProjectionInfo()` 是**系统侧**查询，App 无权限/无 API。
- **结论**：App 拿到的 binder 上**查不到**自己是 display 还是 task 录制。

### 3. `VirtualDisplay` 尺寸 / displayId（唯一理论暴露点）

App 拿到 projection 后自行 `createVirtualDisplay(name, width, height, dpi, flags, surface)`：
- **VD 尺寸由 App 自己传**（通常就是全屏分辨率），不依赖系统告知范围。
- 实际录制范围由 WMS 的 `ContentRecordingSession`（`RECORD_CONTENT_TASK` token）决定，
  **内容在 WMS 层裁切，VD 本身仍可维持全屏尺寸**。
- 反编译 `MediaProjection.applyVirtualDisplayFlags()`（823-835 行）只按 `mType`（0/1/2）改
  flags，**与整屏/单应用无关**（mType 由 `createProjection(type)` 决定，单应用路径 type=0 与整屏相同）。

**结论**：单应用与整屏的 `MediaProjection`/`VirtualDisplay` 对 App **完全同构**，
App 通过公开 API **无法区分**。

---

## 二、为什么"伪装成整屏"不需要额外代码

- App 视角：它拿到 projection → 建 VD（全屏尺寸）→ 收到帧。它**没有**任何 API 查询
  "WMS 正在录 display 还是 task"。
- 系统视角：WMS 按 `ContentRecordingSession` 决定输出目标 task 还是整屏，这是**系统侧**语义，
  App 无感知。
- 因此"让 App 以为自己开了整屏"**已经天然成立**，无需对 App 做任何欺骗。

---

## 三、唯一需要留意的边界（未来风险）

| 场景 | 风险 | 处理 |
|---|---|---|
| App 用反射读服务端 `MediaProjection.mSession` | 高权限/厂商专属 App 可能反射读私有字段 | 无法完全阻止反射；但普通 App 无此权限与动机 |
| App 读 `VirtualDisplay.getDisplay()` 再查 display 是否主屏 | VD 是独立 display id，但**整屏与单应用一致** | 无差异，不暴露 |
| 黑名单 App（pubgmhd/sgame）强制整屏语义 | 本模块 `RangeChoiceHook` 也会放开单应用选项（当前无包名过滤） | 如要保持黑名单强制整屏，需按包名过滤（见 `DESIGN_IMPLEMENTATION.md` 待批准项） |
| 未来 Android 新增"录制目标查询 API" | 一旦平台暴露 session 类型，检测面出现 | 需在发布时跟进平台 API 变化 |

---

## 四、与阶段 7 多会话的关系

- 多会话（阶段 7）改造**服务端** `mProjectionGrant` 单槽为多槽表，**不影响** App 侧检测面。
- 每个会话仍是独立 `MediaProjection`（App 视角同构），`mSession` 仍是私有字段。
- 结论：多会话不会扩大 App 的检测面。

---

## 五、实测验证建议（可选）

如需实测确认"App 看到的 VD 尺寸/display 与整屏无差异"，可在设备上：
1. 用搜题酱/任意录屏 App 走单应用授权；
2. `dumpsys display` 查看 VD 的尺寸与 flags；
3. 对比整屏授权时的 VD 参数。
若两者一致，则检测面结论得到实测背书（当前反编译证据已足够，此步为额外保险）。
