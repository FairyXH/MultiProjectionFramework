# MultiProjection Framework — 环境与构建文档

## 目标
Android 15 / ColorOS 15 上的 LSPosed 模块：多个 App 各自独立的 MediaProjection 授权、录制目标、VirtualDisplay/Surface、Callback、停止生命周期；仅注入 system（system_server）与 com.android.systemui，绝不注入第三方 App。

## 宿主设备（实测 2026-10-11）
| 项目 | 值 |
|---|---|
| 型号 | OPPO PJV110（OnePlus PKG110） |
| 系统版本 | Android 15（SDK 35） |
| ColorOS 版本 | PKG110_15.0.0.802(CN01) |
| Build fingerprint | OnePlus/PKG110/OP5D2BL1:15/UKQ1.231108.001/V.1d9a5f9_1-40496:user/release-keys |
| Root | su 可用（uid=0），KernelSU + Zygisk |
| LSPosed | lspd 进程存活；Manager 2.1.0-it（versionCode 7774，targetSdk 37）；配置库 /data/adb/lspd/config/modules_config.db |
| SystemUI | /system_ext/priv-app/SystemUI/SystemUI.apk |

## Ubuntu 构建机
- 系统：Ubuntu 24（proot），宿主 aarch64（`uname -m` = aarch64）
- JDK：Temurin 21（`/opt/jdk-21.0.12.1+1`，AGP 9.x 必需）
- Gradle：预装 9.3.1（`/opt/gradle-9.3.1`），阿里云镜像 init-script `/opt/gradle-init.gradle`
- Android SDK：`/opt/android-sdk`（platforms android-36 / android-37.1，build-tools 35/36/37，platform-tools）
- GRADLE_USER_HOME：`/opt/gradle-home`
- **无 NDK / CMake / adb / jadx**（jadx 已从官方下载 v1.5.3，见下）

## 工具链已解决项
1. **jadx**：`/opt/jadx.zip` 损坏；从官方 GitHub 重新下载 v1.5.3（116MB，校验 OK）解压到 `/opt/jadx`，`/opt/jadx/bin/jadx --version` = 1.5.3。用法：`jadx --no-res -j N -d <outdir> <input>`。
2. **aapt2**：宿主 aarch64 无法直接运行 x86_64 aapt2。可靠 wrapper：`/opt/aapt2wrap/aapt2`（qemu-amd64-static -L /usr/x86_64-linux-gnu 运行 build-tools/36.0.0/aapt2）。构建时必须加 `-Pandroid.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2`。
   - ⚠️ `/opt/aapt2-qemu/aapt2` 是旧硬编码路径 wrapper（指向 ZSD 缓存），已失效，不要用。
3. **无 adb**：文件传输用 root shell（super_admin:shell）→ /data/local/tmp → pm install；日志用 root 读 logcat / LSPosed 日志目录。

## 构建命令（成功验证）
```bash
cd /home/projects/MultiProjectionFramework
source /opt/android-env.sh
export GRADLE_USER_HOME=/opt/gradle-home
/opt/gradle-9.3.1/bin/gradle :app:assembleDebug \
  --console=plain --no-daemon \
  --init-script /opt/gradle-init.gradle \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2 \
  -Dorg.gradle.internal.http.connectionTimeout=120000 \
  -Dorg.gradle.internal.http.socketTimeout=120000
```
Release 同理（`assembleRelease`，release 默认用 debug keystore 签名可安装）。

## 构建配置
- AGP 9.1.1（gradle/libs.versions.toml），compileSdk 37.1，minSdk 28，targetSdk 37
- libxposed API 102.0.0（compileOnly），纯 Java，无 native/shadowhook
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（2.49MB）

## 项目结构
```
app/src/main/java/com/fairyxh/multiprojection/
  ModuleEntry.java      # API-102 入口
  Bootstrap.java        # 进程内初始化（纯 Java）
  FeatureRegistry.java  # 功能开关（enabled/diagnostics/range_choice/multi_session）
  FeatureState.java     # 状态摘要
  TemplateConfig.java   # 配置：作用域 android+com.android.systemui、LOG_TAG=MPFw
  ui/                   # 悬浮菜单（仅 SystemUI）
app/src/main/resources/META-INF/xposed/   # module.prop / scope.list / java_init.list
```
