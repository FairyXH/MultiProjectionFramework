package com.fairyxh.multiprojection.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 原理 Tab：模块工作原理 + 与「隐屏」等隐私模块的共存说明。
 */
public final class PrinciplesTab {

    private PrinciplesTab() {
    }

    public static LinearLayout build(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(iOSStyle.BG);

        // —— 工作原理 ——
        root.addView(iOSStyle.sectionTitle(c, "工作原理"));
        LinearLayout card1 = iOSStyle.card(c);
        iOSStyle.addCell(card1, block(c, "MediaProjection 自动授权",
                "当规则内的 App 调用 MediaProjectionManager.createScreenCaptureIntent() 发起录屏/投屏时，"
                        + "本模块在 system_server 中拦截授权流程，自动授予权限并注入录制会话，"
                        + "全程不弹窗、不打断用户。"), true);
        root.addView(card1);

        root.addView(iOSStyle.sectionTitle(c, "录制范围模式"));
        LinearLayout card2 = iOSStyle.card(c);
        iOSStyle.addCell(card2, modeRow(c, "0 · 整屏", "录制整个屏幕所有内容（默认）"), false);
        iOSStyle.addCell(card2, modeRow(c, "1 · 单应用-自身", "只录制申请方自己的窗口"), false);
        iOSStyle.addCell(card2, modeRow(c, "2 · 单应用-自定义", "只录制你指定的某个目标应用窗口"), true);
        root.addView(card2);

        // —— 与隐私模块的共存 ——
        root.addView(iOSStyle.sectionTitle(c, "与「隐屏」等隐私模块"));
        LinearLayout card3 = iOSStyle.card(c);
        iOSStyle.addCell(card3, block(c, "能共存，但要理解机制",
                "本模块只做「自动授权」，本身不隐藏任何窗口、不影响录屏内容。"
                        + "「隐屏（SkipScreenshot）类模块」负责把指定 App 的窗口从屏幕截图/录屏中抹掉。"
                        + "两者功能独立，互不干扰。"), false);
        iOSStyle.addCell(card3, block(c, "使用建议",
                "当「隐屏」把某个 App 的窗口设为不截图时，该 App 自己录制屏幕（若它依赖录屏内容识别）"
                        + "会拿到空画面而失败闪烁。这是隐私模块的设计目的（防截图），不是本模块的缺陷。"
                        + "若需要两者在同一个 App 上同时生效，请在「隐屏」中将该 App 从跳过截图的名单中移除，"
                        + "或改用「单应用-自定义」模式只录制目标窗口。"), true);
        root.addView(card3);

        // 底部留白
        TextView footer = new TextView(c);
        footer.setMinimumHeight(iOSStyle.dp(c, 40));
        root.addView(footer);
        return root;
    }

    private static LinearLayout modeRow(Context c, String title, String desc) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        int padV = iOSStyle.dp(c, 10);
        cell.setPadding(0, padV, 0, padV);

        TextView t = iOSStyle.cellTitle(c, title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        cell.addView(t);

        TextView d = iOSStyle.cellDetail(c, desc);
        d.setPadding(0, iOSStyle.dp(c, 3), 0, 0);
        cell.addView(d);
        return cell;
    }

    private static LinearLayout block(Context c, String title, String body) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        int padV = iOSStyle.dp(c, 12);
        cell.setPadding(0, padV, 0, padV);

        TextView t = iOSStyle.cellTitle(c, title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        cell.addView(t);

        TextView d = iOSStyle.cellDetail(c, body);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        d.setLineSpacing(0, 1.25f);
        d.setPadding(0, iOSStyle.dp(c, 4), 0, 0);
        cell.addView(d);
        return cell;
    }
}