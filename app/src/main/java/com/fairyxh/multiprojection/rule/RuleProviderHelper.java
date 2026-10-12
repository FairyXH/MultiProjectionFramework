package com.fairyxh.multiprojection.rule;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;

/**
 * ContentProvider 读取辅助（RuleStore 的兜底路径）。
 *
 * <p>当 /data/misc/mpfw/rules.json 副本缺失时，经
 * content://com.fairyxh.multiprojection.rule/rules 读取（需模块 App 进程存活）。</p>
 */
public final class RuleProviderHelper {
    private RuleProviderHelper() {}

    /** 经 ContentProvider 读取规则 JSON；失败返回 null。 */
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
}