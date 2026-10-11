# MultiProjection Framework — 进度与接力文档

## 项目
- 仓库：`/home/projects/MultiProjectionFramework`（git 已 clone 自 Jordan231111/lsposed-universal-template）
- 包名：`com.fairyxh.multiprojection`，应用名：MultiProjection Framework
- 模板：libxposed API 102（`io.github.libxposed:api:102.0.0` compileOnly），纯 Java 裁剪（无 native/shadowhook）
- 作用域：`android`（system_server）+ `com.android.systemui`（scope.list 已配置）

## 当前进度（2026-10-11 16:30）
| 阶段 | 状态 |
|---|---|
| 阶段 0 环境自检 | ✅ 完成：设备信息、root、LSPosed、JDK21、Gradle 9.3.1、SDK、jadx 1.5.3 就绪；纯 Java 裁剪完成；`assembleDebug` 构建成功 |
| 阶段 1 提取系统文件 | ✅ 完成：services.jar/framework.jar/oplus-services.jar/oplus-framework.jar/SystemUI.apk 完整 DEX 提取 + SHA-256 记录（见 EXTRACTION.md） |
| 阶段 2 JADX 反编译 | ✅ 关键类已单类反编译：MediaProjectionManagerService（完整1272行）、MediaProjectionConfig、ContentRecordingSession、ContentRecorder、ContentRecordingController、SystemUI 授权链（PermissionActivity/ServiceHelper/DialogDelegate/BaseDelegate/ScreenShareOption/AppSelector） |
| 阶段 3 调用链追踪 | ✅ 端到端链路 A/B/C/D 已追踪，写入 ARCHITECTURE.md + SESSION_MODEL.md |
| 阶段 4 方案设计 | ⏳ 进行中（多会话改造受 ContentRecordingController 全局单会话限制） |
| 阶段 5 只读诊断模块 | ⏳ 待编码 |
| 阶段 6 SystemUI 范围选择 | ⏳ 未开始（Hook 点已明确：createOptionList 解除禁用） |
| 阶段 7 多会话系统服务 | ⏳ 未开始（高风险需批准） |
| 阶段 8 测试矩阵 | ⏳ 未开始 |
| 阶段 9 交付 | ⏳ 未开始 |

## 关键技术决策（已确认）
1. 纯 Java 裁剪模板（移除 ShadowHook/native/EngineDetector），不装 NDK/CMake。
2. 构建降级：AGP 9.1.1 + Gradle 9.3.1（与 ZhangSystemDex 同款已验证），compileSdk 37.1。
3. 无 adb：root shell 传输文件；APK 安装用 `pm install`；日志读 logcat。
4. 重启需用户手动执行，Agent 绝不擅自 reboot。

## 已验证结论
- 宿主 aarch64，AGP 的 x86_64 aapt2 必须经 `/opt/aapt2wrap/aapt2`（qemu wrapper）运行，否则 `processDebugResources` 失败。
- `/opt/aapt2-qemu/aapt2` 已失效（硬编码 ZSD 旧缓存路径），勿用。
- jadx 1.5.3 官方包在 `/opt/jadx`，CLI 可用。
- 手机端 LSPosed 运行中，Manager 2.1.0-it；SystemUI 位于 /system_ext/priv-app。

## 下一步（按序）
1. 阶段 1：用 root shell 提取 SystemUI.apk + /system/framework 相关 JAR + odex/vdex/oat，记录 SHA-256 到 `docs/EXTRACTION.md`。
2. 阶段 2：jadx 分批反编译，检索 MediaProjection 相关类，产出 `docs/HOOK_POINTS.md`。

## 风险与提醒
- 阶段 5/7 启用 system_server 注入前必须单独征求用户批准；说明 bootloop 风险与恢复方案。
- 任何需要重启的节点：停止会话，请用户手动重启，不擅自 reboot。
- 长任务请定期更新本文档与 NEXT_AGENT.md。