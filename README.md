# MultiProjection Framework

Android 15 / ColorOS 15 多路 MediaProjection 会话框架（LSPosed 模块）。

## 特性
- 多个 App 可同时拥有独立的 MediaProjection 授权 Token、录制目标、VirtualDisplay/Surface、Callback、停止生命周期。
- 保留系统授权弹窗与一次性 Token 语义；用户每次可自行选择「整个屏幕」或「单个应用」。
- 作用域**仅** `android`（system_server）与 `com.android.systemui`，绝不注入第三方 App。
- 基于 libxposed API 102（`io.github.libxposed:api:102.0.0`），纯 Java。

## 开发环境
见 `docs/ENVIRONMENT.md`（构建命令、工具链、aapt2 wrapper 等）。

## 状态与接力
见 `docs/PROGRESS.md`、`docs/NEXT_AGENT.md`。

## 构建
```bash
cd /home/projects/MultiProjectionFramework
source /opt/android-env.sh
export GRADLE_USER_HOME=/opt/gradle-home
/opt/gradle-9.3.1/bin/gradle :app:assembleRelease \
  --console=plain --no-daemon \
  --init-script /opt/gradle-init.gradle \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2
```
产物：`app/build/outputs/apk/release/app-release.apk`。

## 安装与启用（高风险，需用户批准后执行）
1. `pm install -r <apk>` 安装模块。
2. LSPosed/Vector 中启用模块，作用域勾选 `Android 系统框架` 与 `SystemUI`。
3. 重启（由用户手动执行）。

> 仅在你自己拥有/授权的设备上使用。任何 system_server 级 Hook 都有 bootloop 风险，务必先阅读 `docs/RISKS_AND_RECOVERY.md`。