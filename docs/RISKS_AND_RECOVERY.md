# RISKS_AND_RECOVERY.md — 风险与恢复方案

## 一、风险分级

| 级别 | 操作 | 风险 | 何时发生 |
|---|---|---|---|
| L1 | 编译、单类反编译、文档 | 无 | 任意 |
| L2 | SystemUI 作用域启用（范围选择 Hook） | 低：SystemUI 崩溃 → 状态栏/通知栏异常，系统一般可自行重启 SystemUI | 阶段 6 |
| L3 | system 作用域启用（诊断 Hook） | 中：system_server 异常 → 系统重启/软重启，极少 bootloop | 阶段 5 |
| L4 | system 作用域启用（多会话改造） | 高：system_server 崩溃循环 → **bootloop** | 阶段 7 |

## 二、bootloop 恢复方案（适用于 L3/L4）

### 手段 1：安全模式（推荐首选）
1. 关机 → 长按电源，在开机动画出现时连按音量下（部分 ColorOS 为音量上）进入安全模式。
2. 安全模式下 LSPosed 模块不生效（Zygisk 在安全模式默认禁用），系统可正常启动。
3. 进入 LSPosed Manager → 停用本模块 → 重启。

### 手段 2：adb/root 直接停用（设备能开机但有异常）
```sh
# 若系统能启动到 shell
su -c 'lp help'                       # 查看 LSPosed CLI
su -c 'lp disable com.fairyxh.multiprojection'  # 停用模块
adb reboot
```

### 手段 3：KernelSU 模块层保护（本模块以 APK 安装，不涉及 /data/adb/modules）
- 本模块是普通 APK，不在 /data/adb/modules/。若出现 bootloop，通过安全模式或 recovery 卸载 APK：
```sh
# recovery/高级模式下 adb
adb shell pm uninstall com.fairyxh.multiprojection
```

### 手段 4：最后手段
- KSU 管理器 → 临时禁用 `zygisk_lsposed`（Zygisk 加载器）→ 重启 → LSPosed 不再注入任何模块 → 系统恢复后卸载本模块再启用 zygisk_lsposed。

## 三、预防措施（模块设计层面）
1. **总开关**：`TemplateConfig.ENABLE_*` 默认全关；只有用户显式开启才生效。
2. **ExceptionMode.PROTECTIVE**：所有 Hook 异常仅记录日志，不改变系统行为。
3. **不写系统分区**：所有修改仅内存级 Hook，模块卸载/重启即恢复原系统。
4. **不自动重启**：任何需要重启的场景由用户手动执行。
5. **system_server 热路径不做阻塞 I/O**：会话表内存操作，日志限流。
6. **逐步启用**：先 SystemUI（L2）→ 诊断 system（L3）→ 多会话（L4），每步真实验证后再下一步。

## 四、禁止事项（硬约束）
- ❌ 不自动点击授权弹窗、不绕过用户同意。
- ❌ 不让 `isCurrentProjection` 无条件返回 true。
- ❌ 不复用一次性 Token、不跨 UID 盗用 Token。
- ❌ 不修改 /system、/vendor、/product、/system_ext。
- ❌ 不打印可重用 Token 细节。
- ❌ 不擅自重启设备。