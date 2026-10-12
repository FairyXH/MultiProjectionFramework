package com.fairyxh.multiprojection.rule;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动授权规则读取（system_server 侧使用，通过 ContentProvider 桥）。
 *
 * <p>system_server 进程（uid=1000）无法直接读取模块 App 私有数据目录下的 rules.json，
 * 因此通过 {@link RuleProvider}（content://com.fairyxh.multiprojection.rule/rules）读取。
 * 模块 App 进程自身读取文件并返回 JSON 字符串。</p>
 *
 * <p>system_server 环境：Binder 调用方是 system，content provider 是 exported=true，
 * 授权无碍。读取失败/Provider 未就绪时返回空列表（不影响系统行为）。</p>
 */
public final class RuleStore {
    /** 通过 ContentProvider 读取规则 JSON 字符串；失败返回 null。 */
    public static String loadRulesJson(Context context) {
        if (context == null) return null;
        try {
            ContentResolver cr = context.getContentResolver();
            try (Cursor c = cr.query(RuleProvider.CONTENT_URI, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex("json");
                    if (idx >= 0) return c.getString(idx);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取规则列表；异常时返回空列表（不影响系统行为）。 */
    public static List<RulesConfig.Rule> loadRules(Context context) {
        String json = loadRulesJson(context);
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
}