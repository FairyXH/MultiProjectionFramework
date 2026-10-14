package com.fairyxh.multiprojection.ui;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 首页 Tab：模块状态 + 核心能力 + 快速开始（iOS 风格分组卡片）。
 * 状态为静态说明（LSPosed 模块运行态无法在 App 进程内读取，展示使用指南）。
 */
public final class HomeTab {

    private HomeTab() {
    }

    public static LinearLayout build(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(iOSStyle.BG);

        // —— 模块状态卡 ——
        root.addView(iOSStyle.sectionTitle(c, "模块状态"));
        LinearLayout card1 = iOSStyle.card(c);
        TextView statusTitle = iOSStyle.cellTitle(c, "MultiProjection Framework");
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusTitle.setTypeface(statusTitle.getTypeface(), Typeface.BOLD);
        iOSStyle.addCell(card1, statusTitle, false);

        TextView statusLabel = new TextView(c);
        statusLabel.setText("● 已启用（需重启生效）");
        statusLabel.setTextColor(iOSStyle.ACCENT);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusLabel.setPadding(0, iOSStyle.dp(c, 10), 0, iOSStyle.dp(c, 4));
        iOSStyle.addCell(card1, statusLabel, false);

        TextView scope = iOSStyle.cellDetail(c,
                "作用域：android（system_server）+ com.android.systemui\n"
                        + "框架：libxposed API 102（Vector / LSPatch 1.2）");
        scope.setLineSpacing(0, 1.15f);
        iOSStyle.addCell(card1, scope, true);
        root.addView(card1);

        // —— 核心能力卡 ——
        root.addView(iOSStyle.sectionTitle(c, "核心能力"));
        LinearLayout card2 = iOSStyle.card(c);
        addCapability(card2, "自动授权", "规则内 App 发起录屏/投屏时自动授权，不再弹窗", false);
        addCapability(card2, "录制范围控制", "整屏 / 单应用-自身 / 单应用-自定义三种模式", false);
        addCapability(card2, "多投影会话", "深度改造 MediaProjection 会话链路，支持多路并行", true);
        root.addView(card2);

        // —— 设置卡 ——
        root.addView(iOSStyle.sectionTitle(c, "设置"));
        root.addView(buildSettingsCard(c));

        // —— 快速开始卡 ——
        root.addView(iOSStyle.sectionTitle(c, "快速开始"));
        LinearLayout card3 = iOSStyle.card(c);
        iOSStyle.addCell(card3, stepRow(c, "1", "在 LSPosed / Vector 中启用本模块"), false);
        iOSStyle.addCell(card3, stepRow(c, "2", "勾选作用域：Android 系统框架 + SystemUI"), false);
        iOSStyle.addCell(card3, stepRow(c, "3", "重启设备后生效，前往「规则」配置自动授权"), true);
        root.addView(card3);

        // 底部留白
        TextView footer = new TextView(c);
        footer.setMinimumHeight(iOSStyle.dp(c, 40));
        root.addView(footer);
        return root;
    }

    private static void addCapability(LinearLayout card, String title, String desc, boolean last) {
        LinearLayout cell = new LinearLayout(card.getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        int padV = iOSStyle.dp(card.getContext(), 10);
        cell.setPadding(0, padV, 0, padV);

        TextView t = iOSStyle.cellTitle(card.getContext(), title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cell.addView(t);

        TextView d = iOSStyle.cellDetail(card.getContext(), desc);
        d.setPadding(0, iOSStyle.dp(card.getContext(), 3), 0, 0);
        cell.addView(d);
        iOSStyle.addCell(card, cell, last);
    }

    private static LinearLayout stepRow(Context c, String num, String text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = iOSStyle.dp(c, 12);
        row.setPadding(0, padV, 0, padV);

        TextView badge = new TextView(c);
        badge.setText(num);
        badge.setTextColor(iOSStyle.ACCENT);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
        row.addView(badge, new LinearLayout.LayoutParams(
                iOSStyle.dp(c, 28), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView txt = iOSStyle.cellTitle(c, text);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(txt);
        return row;
    }

    /**
     * 设置卡：隐藏/恢复桌面图标。
     * 通过 PackageManager.setComponentEnabledSetting 禁用 LauncherActivity
     * 的 COMPONENT_ENABLED_STATE_DISABLED，桌面图标即消失；再次点击恢复。
     */
    private static LinearLayout buildSettingsCard(Context c) {
        LinearLayout card = iOSStyle.card(c);

        // 隐藏桌面图标行
        LinearLayout hideRow = new LinearLayout(c);
        hideRow.setOrientation(LinearLayout.HORIZONTAL);
        hideRow.setGravity(Gravity.CENTER_VERTICAL);
        int padV = iOSStyle.dp(c, 12);
        hideRow.setPadding(0, padV, 0, padV);

        TextView hideTitle = new TextView(c);
        hideTitle.setText("隐藏桌面图标");
        hideTitle.setTextColor(iOSStyle.TEXT);
        hideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hideRow.addView(hideTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hideStatus = new TextView(c);
        hideStatus.setTextColor(iOSStyle.ACCENT);
        hideStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hideStatus.setGravity(Gravity.RIGHT);
        updateHideStatus(c, hideStatus);
        hideRow.addView(hideStatus);
        hideRow.setOnClickListener(v -> {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c.getPackageName(),
                    "com.fairyxh.multiprojection.ui.LauncherActivity");
            int state = pm.getComponentEnabledSetting(cn);
            int newState = state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(cn, newState, PackageManager.DONT_KILL_APP);
            updateHideStatus(c, hideStatus);
        });
        hideRow.setClickable(true);
        iOSStyle.addCell(card, hideRow, true);

        // 隐藏后恢复提示
        TextView tip = iOSStyle.cellDetail(c,
                "隐藏后需通过模块管理器或重新安装 App 找回入口，恢复图标同样在此页面。");
        tip.setPadding(0, iOSStyle.dp(c, 6), 0, iOSStyle.dp(c, 12));
        iOSStyle.addCell(card, tip, true);
        return card;
    }

    private static void updateHideStatus(Context c, TextView status) {
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c.getPackageName(),
                    "com.fairyxh.multiprojection.ui.LauncherActivity");
            int state = pm.getComponentEnabledSetting(cn);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                status.setText("已隐藏 · 点击恢复");
            } else {
                status.setText("正常 · 点击隐藏");
            }
        } catch (Throwable t) {
            status.setText("未知");
        }
    }
}