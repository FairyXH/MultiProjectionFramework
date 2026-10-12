package com.fairyxh.multiprojection.rule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动授权规则读取与缓存（system_server 侧使用）。
 *
 * <p>规则文件位于模块 App 数据目录 files/rules.json，由模块 App 的配置页（RulesActivity）
 * 写入。system_server 拥有 root 权限，可直接读取该文件。</p>
 *
 * <p>由于 system_server 常驻且规则文件可能被模块 App 修改，读取时采用"短 TTL 缓存"：
 * 每次判断都重新读取（文件很小），避免缓存失效问题；不做跨进程通知。</p>
 */
public final class RuleStore {
    private static final String TAG = "MPFw-Rules";
    private static volatile String sModuleDataDir;

    private RuleStore() {}

    /** 设置模块 App 数据目录（system_server 侧，需 root 读权限）。 */
    public static void setModuleDataDir(String dir) {
        sModuleDataDir = dir;
    }

    /** 读取规则列表；异常时返回空列表（不影响系统行为）。 */
    public static List<RulesConfig.Rule> loadRules() {
        String dir = sModuleDataDir;
        if (dir == null || dir.isEmpty()) return new ArrayList<>(0);
        return RulesConfig.load(new File(dir, "files"));
    }

    /** 命中规则则返回对应规则，否则 null。 */
    public static RulesConfig.Rule findRule(String appPkg) {
        if (appPkg == null || appPkg.isEmpty()) return null;
        return RulesConfig.findFor(loadRules(), appPkg);
    }
}