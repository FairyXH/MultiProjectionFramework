package com.fairyxh.multiprojection.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * iOS 风格 UI 工具：颜色、dp 换算、圆角卡片、分组标题、cell 行。
 * 设计参考 iOS Inset Grouped：浅灰背景 #F2F2F7、白色圆角卡片、主色 #007AFF。
 */
public final class iOSStyle {
    public static final int BG = Color.rgb(242, 242, 247);
    public static final int CARD = Color.WHITE;
    public static final int TEXT = Color.rgb(17, 17, 17);
    public static final int TEXT_SECONDARY = Color.rgb(142, 142, 147);
    public static final int TEXT_TERTIARY = Color.rgb(199, 199, 204);
    public static final int ACCENT = Color.rgb(0, 122, 255);
    public static final int SEPARATOR = Color.rgb(229, 229, 234);
    public static final int DANGER = Color.rgb(255, 59, 48);

    private iOSStyle() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** 白色圆角卡片背景。 */
    public static GradientDrawable cardBg(Context c, float cornerDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(c, cornerDp));
        return g;
    }

    /** 分组标题（iOS Section header，大写灰色加粗）。 */
    public static TextView sectionTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text.toUpperCase());
        t.setTextColor(TEXT_SECONDARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        int p = dp(c, 16);
        t.setPadding(p + 4, dp(c, 12), p, dp(c, 6));
        return t;
    }

    /** 创建卡片容器（垂直，圆角 14dp，含左右外边距）。 */
    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(c, 14));
        card.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 16), 0, dp(c, 16), dp(c, 14));
        card.setLayoutParams(lp);
        return card;
    }

    /** cell 主文案。 */
    public static TextView cellTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** cell 副文案（灰）。 */
    public static TextView cellDetail(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT_SECONDARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return t;
    }

    /** 在 card 中追加一行 cell，带分隔线（最后一个不加）。 */
    public static void addCell(LinearLayout card, View cellView, boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(cellView, lp);
        if (!last) {
            View sep = new View(card.getContext());
            sep.setBackgroundColor(SEPARATOR);
            card.addView(sep, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }
}