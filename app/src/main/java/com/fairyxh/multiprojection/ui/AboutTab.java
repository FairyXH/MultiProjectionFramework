package com.fairyxh.multiprojection.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 关于 Tab：版本信息、作用域、技术说明。
 */
public final class AboutTab {

    private AboutTab() {
    }

    public static LinearLayout build(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(iOSStyle.BG);

        // 标题区
        LinearLayout titleBlock = new LinearLayout(c);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(0, iOSStyle.dp(c, 28), 0, iOSStyle.dp(c, 18));
        titleBlock.setGravity(android.view.Gravity.CENTER);

        TextView name = new TextView(c);
        name.setText("MultiProjection Framework");
        name.setTextColor(iOSStyle.TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setGravity(android.view.Gravity.CENTER);
        titleBlock.addView(name);

        TextView sub = new TextView(c);
        sub.setText("MediaProjection 多路投影会话框架\nBy FairyXH");
        sub.setTextColor(iOSStyle.TEXT_SECONDARY);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, iOSStyle.dp(c, 6), 0, 0);
        titleBlock.addView(sub);
        root.addView(titleBlock);

        // 信息卡
        root.addView(iOSStyle.sectionTitle(c, "信息"));
        LinearLayout card1 = iOSStyle.card(c);
        iOSStyle.addCell(card1, infoRow(c, "版本", com.fairyxh.multiprojection.BuildConfig.VERSION_NAME), false);
        iOSStyle.addCell(card1, infoRow(c, "框架", "libxposed API 102"), false);
        iOSStyle.addCell(card1, infoRow(c, "作用域", "android + com.android.systemui"), true);
        root.addView(card1);

        // 技术说明
        root.addView(iOSStyle.sectionTitle(c, "技术说明"));
        LinearLayout card2 = iOSStyle.card(c);
        TextView body = new TextView(c);
        body.setText(
                "本模块运行在 system_server（Android 系统框架）进程，"
                        + "深度接管 MediaProjectionManagerService 的授权与会话链路：\n\n"
                        + "· 拦截 App 的录屏授权请求，命中规则即自动放行；\n"
                        + "· 按规则注入整屏 / 单应用录制会话；\n"
                        + "· 支持多路投影会话并行；\n"
                        + "· 规则文件经 su 同步到 /data/misc/mpfw/，\n"
                        + "   system_server 直读，重启立即可用。\n\n"
                        + "仅注入系统进程，绝不注入任何第三方 App。");
        body.setTextColor(iOSStyle.TEXT_SECONDARY);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, iOSStyle.dp(c, 12), 0, iOSStyle.dp(c, 12));
        iOSStyle.addCell(card2, body, true);
        root.addView(card2);

        // 底部留白
        TextView footer = new TextView(c);
        footer.setMinimumHeight(iOSStyle.dp(c, 40));
        root.addView(footer);
        return root;
    }

    private static LinearLayout infoRow(Context c, String label, String value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, iOSStyle.dp(c, 10), 0, iOSStyle.dp(c, 10));

        TextView l = iOSStyle.cellTitle(c, label);
        l.setGravity(android.view.Gravity.LEFT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(l, lp);

        TextView v = iOSStyle.cellDetail(c, value);
        v.setTextColor(iOSStyle.TEXT_SECONDARY);
        v.setGravity(android.view.Gravity.RIGHT);
        row.addView(v);
        return row;
    }
}