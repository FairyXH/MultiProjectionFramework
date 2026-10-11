# SESSION_MODEL.md — 会话模型分析（多会话改造基础）

> 基于本机反编译（`jadx/`），描述"一个活跃投影"的完整生命周期，以及多会话改造的数据结构设计。

## 1. 当前"单会话"模型（每轨单槽）

```
MediaProjectionManagerService
 ├─ mProjectionGrant (普通App)     ── mProjectionToken
 ├─ mOplusProjectionGrant (录屏)   ── mOplusProjectionToken
 └─ mDeathEaters: Map<IBinder, DeathRecipient>   ← watcher callback 死亡
     （注意：只管理 watcher callback 的 death，不管理投影 token）

MediaProjection (每 createProjection 一个)
 ├─ mCallback (IMediaProjectionCallback)   ← App 的 onStop 回调
 ├─ mDeathEater (IBinder.DeathRecipient)   ← callback binder 死亡
 ├─ mSession (ContentRecordingSession)
 ├─ mToken (callback.asBinder)
 ├─ mVirtualDisplayId = -1
 ├─ mTaskId / mLaunchCookie
 └─ mCountStarts / mTimeoutMs(5min) / mCreateTimeMs

CallbackDelegate（两个：普通 + oplus）
 ├─ mClientCallbacks: Map<IBinder, IMediaProjectionCallback>
 └─ mWatcherCallbacks: Map<IBinder, IMediaProjectionWatcherCallback>
```

**单会话语义关键点**：
1. `mProjectionGrant` 只有一个 slot，新启动会 stop 旧的。
2. `isCurrentProjection(token)` 决定一切权限（stop、setSession、reviewConsent）。
3. `setContentRecordingSession` 只作用于 `mProjectionGrant.mSession`。
4. `getActiveProjectionInfo()` 单值返回。
5. Callback 无会话过滤，停止一个会通知所有 client/watcher。

## 2. 多会话目标模型（设计草案）

```
MediaProjectionManagerService（改造后）
 ├─ Map<IBinder, MediaProjection> mProjections  ← token → 会话（替代 mProjectionGrant 单槽）
 ├─ Map<IBinder, IBinder.DeathRecipient> mDeathEaters  ← 保留，扩展至投影 death
 └─ CallbackDelegate 改造：每会话关联自己的 client/watcher 回调子集
```

每个会话（MediaProjection）独立持有：
- 独立 token、uid、packageName、type
- 独立 mSession（ContentRecordingSession：display 或 task）
- 独立 mVirtualDisplayId、mTaskId、mLaunchCookie
- 独立 mDeathEater / callback 注册
- 独立停止流程（stop 只停自己，不影响其他会话）

## 3. 改造需要替换的逻辑（逐项）

| 现状方法 | 多会话替换 | 说明 |
|---|---|---|
| `startProjectionLocked` stop 旧的 | 不 stop 旧会话，只 `mProjections.put(token, projection)` | 需求 4：启动 B 不停 A |
| `stopProjectionLocked` 清空单槽 | 只移除自己 token 对应的会话 | 需求 4：停止 B 不停 A/C |
| `isCurrentProjection(token)` | `mProjections.containsKey(token)` | 需求 6：保留独立有效性校验 |
| `getProjectionInternal` | 查 Map，仍保留一次性 Token 规则 | 需求 6 |
| `setContentRecordingSession` | 查 token 对应会话，只改该会话 session | 需求 4/5 |
| `stopActiveProjection()` | 需带 token 参数或停指定会话 | 需求 6 |
| CallbackDelegate dispatch | 只通知属于该投影的 callback | 需求 4 |
| `handleForegroundServicesChanged` | 只停 uid 匹配的会话 | 需求 7 |
| MediaRouter 路由变化 | 停所有会话（或提示） | 需求 7 |
| `dump()` | 遍历所有会话 | 诊断 |

## 4. 与 WindowManager 的映射（关键限制）

多会话 Token/生命周期可纯 Java 改造，但**真正的多画面输出**受限于：

1. **ContentRecordingController 全局单 mSession**（`jadx/services/.../ContentRecordingController.java`）：
   - `setContentRecordingSessionLocked(incoming, wm)` 会覆盖旧 session。
   - 要支持"同屏多路"，需替换为 `Map<Integer(displayId), ContentRecordingSession>`。

2. **ContentRecorder 每 Display 单实例**（`jadx/services/.../ContentRecorder.java`）：
   - 每 `DisplayContent` 一个 `ContentRecorder`、一个 `mRecordedSurface`。
   - 同一 Display 的多路镜像需要多个 recorder/surface → **受 SurfaceFlinger 限制**。

3. **可行路径**：
   - 每个投影会话绑定**独立 VirtualDisplay**（不同 display id）。
   - 每个 VD 有独立 DisplayContent → 每个 VD 一个 ContentRecorder → **不同 Display 之间可并行**。
   - 但 `ContentRecordingController` 的单 session 语义仍是瓶颈，需改造为多 session 表。
   - **同一真实 Display 内容被多个 VD 同时镜像是否可行**：属 SurfaceFlinger Native 能力，Java Hook 无法保证，**必须真机实测**（阶段 8 测试项 1/2）。

## 5. 一次性 Token 语义（须保留，不可绕过）

Android 15 规则（ColorOS 同样实现）：
- `getProjectionInternal` 仅在 `mProjectionGrant.mSession.isWaitingForConsent()` 且 uid/pkg 匹配时复用。
- `isValid()`：超时(5min)/重复 start/已建 VD → 无效，SecurityException。
- 多会话改造**必须保留**：每个会话独立超时/计数/VD 检查，禁止"让所有 Token 恒有效"。

## 6. 性能与安全预算

- system_server 热路径（startProjectionLocked/stopProjectionLocked/setContentRecordingSession）不做阻塞 I/O、不全量日志。
- 会话表用 `ArrayMap`/`HashMap` + 锁，O(1) 查询。
- 日志限流：每个会话启动/停止最多记 1 条；Token 不打印可重用细节。
- 任何会话异常只影响该会话，不影响系统其他部分（ExceptionMode.PROTECTIVE）。