package com.fairyxh.multiprojection.rule;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动授权规则读取（system_server 侧使用）。
 *
 * <p><b>主路径（推荐）</b>：直读 {@code /data/misc/mpfw/rules.json}。
 * 该文件由模块 App 配置页保存时经 su(root) 同步写入（目录 755 + 文件 644），
 * system_server（uid=1000）已验证可读。此路径<b>不依赖模块 App 进程存活</b>，
 * 重启后立即可用。</p>
 *
 * <p><b>兜底路径</b>：ContentProvider（content://com.fairyxh.multiprojection.rule/rules），
 * 仅当 /data/misc 副本缺失时使用；需模块 App 进程存活。</p>
 */
public final class RuleStore {
    /** system_server 可读的全局规则文件（root 写入，system 读取）。 */
    public static final String SYSTEM_RULES_PATH = "/data/misc/mpfw/rules.json";

    /** 读取规则列表；主路径失败则回退 Provider；全部失败返回空列表（不影响系统行为）。 */
    public static List<RulesConfig.Rule> loadRules(Context context) {
        String json = readSystemRulesFile();
        if (json == null || json.isEmpty() || "null".equals(json)) {
            json = RuleProviderHelper.loadRulesJson(context);
        }
        if (json == null || json.isEmpty() || "null".equals(json)) {
            return new ArrayList<>(0);
        }
        return RulesConfig.parse(json);
    }

    /** 命中规则则返回对应规则，否则 null。 */
    public static RulesConfig.Rule findRule(Context context, String appPkg) {
        if (appPkg == null || appPkg.isEmpty()) return null;
        return RulesConfig.findFor(loadRules(context), appPkg);
    }

    /** 直读 /data/misc/mpfw/rules.json（system_server 进程 uid=1000 可读）。 */
    public static String readSystemRulesFile() {
        try {
            File f = new File(SYSTEM_RULES_PATH);
            if (!f.exists() || !f.isFile() || f.length() > 64 * 1024) return null;
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}