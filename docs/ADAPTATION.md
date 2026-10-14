# ADAPTATION.md — 新设备适配指南

> 面向「把 MultiProjection Framework 从一个已验证设备（OPPO PJV110 / ColorOS 15 / Android 15）迁移到新设备」的完整操作手册。
> 核心思路：**先确认设备基线 → 提取/确认系统 Hook 点 → 用 buildSrc 自动版本构建 → 安装验证 → 按需调整配置**。

## 1. 新设备前置检查（适配前必查）

在开始任何移植前，先用 root shell 确认基线：

```bash
# 1. 设备 / 系统信息
getprop ro.product.model
getprop ro.build.version.release          # Android 版本（需 >= 10，minSdk 28）
getprop ro.build.version.sdk
getprop ro.build.version.security_patch

# 2. root 能力
which su && su -c 'id'                     # 需 root（KernelSU / Magisk / APatch）

# 3. LSPosed / 框架
pm list packages | grep -iE 'lsposed|vector|rish'
# 期望：lspd / com.android.lsposed 运行中，或 Vector 2.2、LSPatch 1.2

# 4. 系统框架可注入性
ls -la /system/framework/services.jar /system/framework/framework.jar 2>/dev/null
ls -la /system_ext/priv-app/SystemUI/SystemUI.apk 2>/dev/null

# 5. 作用域相关服务
service list | grep -i media_projection
```

**判定标准：**
| 检查项 | 必须满足 | 不满足时的处理 |
|---|---|---|
| Android 版本 | ≥ 10（API 29），建议 14/15 | 版本过旧需调整 Hook 点（见 §5） |
| root | 必须有（su 可用） | 本模块依赖 root 同步规则文件与系统级权限 |
| LSPosed/Vector | 运行中，API ≥ 102 | 先装框架，或用 LSPatch 打包目标 App |
| MediaProjection 服务 | `media_projection` 在 service list 中 | 版本差异大，走 §4 重新确认 Hook 点 |

## 2. 构建环境（本机已就绪，换新机器才需要）

```bash
# 依赖：JDK 17+、Android SDK（platform 37 / build-tools 37）、Gradle 9.3.1
source /opt/android-env.sh   # 或配置 ANDROID_HOME
export GRADLE_USER_HOME=/opt/gradle-home
```

关键点：
- **aapt2**：x86_64 宿主构建 ARM 设备 APK 时，AGP 自带 x86_64 aapt2 即可；本环境因 proot 需用 `/opt/aapt2wrap/aapt2` 包装（qemu）。新机器正常安装 SDK 即可。
- **buildSrc 自动版本**：无需手动改版本号。`GitVersion.java` 读 Git 提交次数 → versionCode；与 `version.properties` 取 max 防回退。**新机器 clone 后注意保留 `version.properties` 或在首次构建前删除（无 Git 环境会 +1）**。

## 3. 构建与安装（新设备）

```bash
cd /home/projects/MultiProjectionFramework
/opt/gradle-9.3.1/bin/gradle :app:assembleDebug \
  --console=plain --no-daemon --init-script /opt/gradle-init.gradle \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2 -x lint

# 部署
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/MPFw.apk
# 新设备上（root shell）
cp /sdcard/Download/MPFw.apk /data/local/tmp/MPFw.apk
pm install -r -d /data/local/tmp/MPFw.apk
```

> ⚠️ 若新设备无法访问 /sdcard 下载目录，可用 `adb push` 或 MT 管理器直接拷贝到 /data/local/tmp。

## 4. 新设备 Hook 点确认（关键！）

模块 Hook 的是 **MediaProjection 授权/会话链路**。不同厂商（ColorOS / MIUI / OriginOS / 原生 AOSP）实现差异大。适配前务必重新确认：

### 4.1 服务端（system_server）
```bash
# 提取 services.jar 并反编译 MediaProjectionManagerService
# 参考 docs/EXTRACTION.md 的单类反编译方法
jadx --single-class com.android.server.media.projection.MediaProjectionManagerService services.jar
```
**核对项（对照 docs/HOOK_POINTS.md）：**
- [ ] `mProjectionGrant` / `mOplusProjectionGrant` 字段名是否存在（ColorOS 特有双轨；原生 AOSP 可能只有单轨）
- [ ] `startProjectionLocked` / `stopProjectionLocked` 方法签名
- [ ] `isCurrentProjection` / `setContentRecordingSession` 是否存在
- [ ] Oplus 扩展 `IMediaProjectionManagerServiceExt`（非 OPPO 设备没有）

### 4.2 授权链（SystemUI）
```bash
# 提取 SystemUI.apk，反编译 MediaProjectionPermissionActivity / DialogDelegate
jadx --single-class com.android.systemui.media.MediaProjectionPermissionActivity SystemUI.apk
```
**核对项：**
- [ ] `grantMediaProjectionPermission(int)` 方法（mode=1 整屏 / mode=0 单应用）
- [ ] `createOptionList` 是否存在禁用单应用的逻辑（ColorOS 特有）
- [ ] `hasProjectionPermission(uid, pkg)` 免弹窗路径（Android 14+ 原生）

### 4.3 结论与配置调整
若 Hook 点签名与 `docs/HOOK_POINTS.md` 不一致（厂商定制差异）：
1. 更新 `AutoGrantHook.java` / `RangeChoiceHook.java` 的方法名/参数适配；
2. 用 `--single-class` 反编译确认后再改，**不要凭猜测改代码**；
3. 改动后跑 `docs/VERIFICATION.md` §6 的 Hook 侧验证。

## 5. 配置项调整（按设备）

`app/src/main/java/com/fairyxh/multiprojection/TemplateConfig.java`：

| 配置 | 默认 | 新设备建议 |
|---|---|---|
| `TARGET_PACKAGES` | `android` + `com.android.systemui` | 保持不变（只注入系统） |
| `AUTO_GRANT_ENABLED` | true | 首装建议先关（false）验证基础链路，再开启 |
| `RANGE_CHOICE_ENHANCED` | true | 非 ColorOS 设备确认 `createOptionList` 存在再开 |
| `MULTI_SESSION_ENABLED` | false | **保持 false**（高风险，需充分验证后显式开启） |
| `DIAGNOSTICS_ENABLED` | true | 保持 true（首装排查用） |

## 6. 新设备验证清单

按 `docs/VERIFICATION.md` 跑一遍，特别关注：
- [ ] **重启后** LSPosed 日志 `Loaded in process=system` / `com.android.systemui`
- [ ] 授权弹窗行为：规则 App 自动授权 / 非规则 App 正常弹窗
- [ ] system_server 无 crash / bootloop
- [ ] 录屏会话可正常开始/结束

## 7. 常见适配问题速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `Loaded in process=` 无 system_server 日志 | LSPosed 作用域未勾选 `Android 系统框架` | Manager 中勾选并重启 |
| Hook 无效果但不崩溃 | 厂商改了方法签名/字段名 | §4 重新反编译确认 Hook 点 |
| App 安装后「未激活」 | Vector/LSPatch 未把本模块加入 | 在框架内重新启用 |
| 规则不同步 | `/data/misc/mpfw/` 权限/路径差异 | 检查 RuleProvider + su 同步路径 |
| 系统应用无法被注入 | 厂商加固 / 禁用 ART hook | 用 LSPatch 方案，或确认模块作用域 |
| 首次构建 versionCode 异常 | version.properties 缺失/被删 | 删除后重建，或提交保留 |

## 8. 适配完成标准

- [ ] §1 基线检查全部通过
- [ ] §4 Hook 点核对完成，代码按需调整
- [ ] 构建 + 安装成功（buildSrc 自动版本）
- [ ] `docs/VERIFICATION.md` §4~§6 验证通过
- [ ] 回归清单无崩溃
- [ ] 若有代码改动 → git commit + push，更新本文档