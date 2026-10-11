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
1. **阶段 5**：编码只读诊断模块（Hook MediaProjectionManagerService 关键方法只读日志，不修改行为）。
2. 构建 → 请用户安装 APK + 在 LSPosed 勾选作用域 → 用户手动重启 → 验证日志。
3. 阶段 6：SystemUI 范围选择 Hook（createOptionList 解除禁用）。
4. 阶段 7（需再次批准）：多会话系统服务改造。

## 风险提醒
- 阶段 5/7 启用 system 作用域前必须获用户批准 + 说明 bootloop 风险。
- 任何重启由用户手动执行，Agent 不 reboot。
- 大文件（extracted/、jadx/、logs/、builds/）已 gitignore，不入库。