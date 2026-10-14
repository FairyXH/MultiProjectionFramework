# VERIFICATION.md — 验证文档

> 本文档定义 MultiProjection Framework 从「构建 → 安装 → 功能验证 → 回归」的完整验证流程。
> 每次发布 / 重大改动后，按本清单逐项验证并打勾。

## 0. 验证环境基线

| 项 | 值 |
|---|---|
| 设备 | OPPO PJV110（ColorOS 15，Android 15 API 35） |
| LSPosed | 运行中（lspd），Manager 2.1.0-it |
| 框架 | libxposed API 102（Vector 2.2 / LSPatch 1.2 运行时提供） |
| 作用域 | `android`（system_server）+ `com.android.systemui` |
| 构建工具 | Gradle 9.3.1 + AGP 9.1.1，compileSdk 37.1 / minSdk 28 / targetSdk 37 |

## 1. 构建验证

```bash
cd /home/projects/MultiProjectionFramework
/opt/gradle-9.3.1/bin/gradle :app:assembleDebug \
  --console=plain --no-daemon --init-script /opt/gradle-init.gradle \
  -Pandroid.aapt2FromMavenOverride=/opt/aapt2wrap/aapt2 -x lint
```

**通过标准：**
- [ ] `BUILD SUCCESSFUL`，退出码 0
- [ ] 产物 `app/build/outputs/apk/debug/app-debug.apk` 存在
- [ ] `version.properties` 自动生成/更新，`VERSION_CODE = Git 提交次数`

用 aapt2 校验 APK 元数据：

```bash
/opt/aapt2wrap/aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk \
  | grep -E "package: name|versionCode|versionName|application-label"
```

**通过标准：**
- [ ] `package: name='com.fairyxh.multiprojection'`
- [ ] `versionCode` 与 `version.properties` 一致
- [ ] `versionName` 符合 1.x.y（由 buildSrc 自动计算）
- [ ] `application-label:'MultiProjection Framework'`

## 2. 图标验证

- [ ] APK 内包含 6 个密度图标（`unzip -l` 可见 `res/mipmap-{ldpi,mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}-v4/ic_launcher.png`）
- [ ] 安装后桌面图标为 logo 图（非系统默认灰色图标）

## 3. 安装验证

```bash
# 从 Ubuntu 环境复制到 sdcard
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/MPFw.apk
# Android shell：先拷到 /data/local/tmp 再安装
cp /sdcard/Download/MPFw.apk /data/local/tmp/MPFw-install.apk
pm install -r -d /data/local/tmp/MPFw-install.apk
```

**通过标准：**
- [ ] 返回 `Success`
- [ ] `pm list packages | grep fairyxh` 可见 `com.fairyxh.multiprojection`
- [ ] 通知栏提示「已更新，由于作用域包含系统框架，需重启以应用更改」（预期，重启后生效）

## 4. 模块激活验证（需重启后）

> 重启由用户手动执行（Agent 绝不擅自 reboot）。

- [ ] LSPosed Manager 中本模块显示「已启用」，作用域勾选 `Android 系统框架` + `SystemUI`
- [ ] `logcat -d | grep -i MPFw` 可见：
  - `Loaded in process=system`（system_server 侧）
  - `Loaded in process=com.android.systemui`（SystemUI 侧）
  - `onSystemServerStarting, installing diagnostics`
- [ ] system_server 进程无崩溃 / bootloop

## 5. 功能验证（App 侧）

### 5.1 首页（HomeTab）
- [ ] 模块状态卡：标题「MultiProjection Framework」+ 状态「● 已启用（需重启生效）」+ 作用域说明
- [ ] 核心能力卡：自动授权 / 录制范围控制 / 多投影会话 三项
- [ ] 设置卡：出现「隐藏桌面图标」行，右侧状态初始为「正常 · 点击隐藏」
- [ ] 快速开始卡：3 步指引

### 5.2 隐藏桌面图标
- [ ] 点击「隐藏桌面图标」→ 状态变为「已隐藏 · 点击恢复」
- [ ] 返回桌面，App 图标消失
- [ ] 重新进入 App（经模块管理器 / 重新安装 APK 打开）→ 点击「已隐藏 · 点击恢复」→ 状态变回「正常 · 点击隐藏」
- [ ] 桌面图标恢复显示

### 5.3 规则（RulesTab）
- [ ] 「自动授权规则」为空态提示正常
- [ ] 可添加规则（输入包名 + 选择模式）
- [ ] 规则写入 `files/rules.json`（经 su 同步 `/data/misc/mpfw/rules.json`）
- [ ] 规则列表显示 / 删除正常

### 5.4 原理（PrinciplesTab）
- [ ] 工作原理卡片正常显示
- [ ] 录制范围模式说明正常
- [ ] 与「隐屏」等隐私模块共存说明正常

### 5.5 关于（AboutTab）
- [ ] 版本号显示 = 当前 versionName（动态读取 BuildConfig）
- [ ] 框架 / 作用域信息正确

### 5.6 启动稳定性
- [ ] 冷启动不崩溃（无 `IllegalStateException: child already has a parent`）
- [ ] 4 个 Tab 来回切换 10 次以上无崩溃 / 无重复 addView 异常

## 6. 功能验证（Hook 侧，需重启后）

- [ ] 规则内 App 发起录屏：自动授权、不弹窗（`AUTO_GRANT_ENABLED=true` 且命中规则）
- [ ] 非规则 App 发起录屏：正常弹授权窗（默认行为不变）
- [ ] `logcat -d | grep MPFw` 有自动授权命中日志

## 7. 回归清单

每次改动后至少跑一遍：
- [ ] 构建成功（含 version.properties 未回退）
- [ ] 安装成功
- [ ] App 4 Tab 切换无崩溃
- [ ] 隐藏/恢复图标往返正常
- [ ] 规则增删改查正常
- [ ] system_server 无异常崩溃日志

## 8. 发布前检查

- [ ] `docs/` 全部文档已更新（PROGRESS / NEXT_AGENT / 本文件）
- [ ] `version.properties` 已提交（保证版本不回退）
- [ ] 代码已 `git push` 到 `FairyXH/MultiProjectionFramework`
- [ ] GitHub Release 已创建，附 APK 产物
- [ ] APK 由 buildSrc 自动版本命名（如 `MPFw-v1.4.x.apk`）
