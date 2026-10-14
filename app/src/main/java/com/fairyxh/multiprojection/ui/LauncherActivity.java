package com.fairyxh.multiprojection.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块主界面：iOS 风格（浅灰背景 + 白色分组卡片 + 大标题 + 底部 Tab 栏）。
 * 四个 Tab：首页 / 规则 / 原理 / 关于。
 */
public final class LauncherActivity extends Activity {

    private static final String[] TAB_TITLES = {"首页", "规则", "原理", "关于"};
    private static final String[] TAB_ICONS = {"⌂", "⚙", "ℹ", "●"};

    private LinearLayout contentHost;
    private TextView[] tabLabels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyIosChrome();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(iOSStyle.BG);

        // —— 顶部大标题（iOS Large Title）——
        TextView title = new TextView(this);
        title.setText("MultiProjection");
        title.setTextColor(iOSStyle.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setPadding(dp(20), dp(14), dp(20), dp(4));
        root.addView(title);

        // —— 内容区（可滚动）——
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(contentHost);

        // —— 底部 Tab 栏 ——
        root.addView(buildBottomBar());

        setContentView(root);
        selectTab(0);
    }

    private void applyIosChrome() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setStatusBarColor(iOSStyle.BG);
            }
        } catch (Throwable ignored) {
        }
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(iOSStyle.CARD);

        View divider = new View(this);
        divider.setBackgroundColor(iOSStyle.SEPARATOR);
        bar.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(10));
        bar.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabLabels = new TextView[TAB_TITLES.length];
        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setOnClickListener(v -> selectTab(index));

            TextView icon = new TextView(this);
            icon.setText(TAB_ICONS[i]);
            icon.setTextColor(iOSStyle.TEXT_TERTIARY);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            icon.setGravity(Gravity.CENTER);

            TextView label = new TextView(this);
            label.setText(TAB_TITLES[i]);
            label.setTextColor(iOSStyle.TEXT_TERTIARY);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(2), 0, 0);

            item.addView(icon);
            item.addView(label);
            row.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabLabels[i] = label;
        }
        return bar;
    }

    private void selectTab(int index) {
        // 更新底栏选中态
        if (tabLabels != null) {
            for (int i = 0; i < tabLabels.length; i++) {
                if (tabLabels[i] != null) {
                    tabLabels[i].setTextColor(i == index ? iOSStyle.ACCENT : iOSStyle.TEXT_TERTIARY);
                    tabLabels[i].setTypeface(null, i == index ? Typeface.BOLD : Typeface.NORMAL);
                }
            }
        }
        // 切换内容
        View page = buildPage(index);
        contentHost.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View buildPage(int index) {
        switch (index) {
            case 1:
                return new RulesTab(this).build();
            case 2:
                return PrinciplesTab.build(this);
            case 3:
                return AboutTab.build(this);
            case 0:
            default:
                return HomeTab.build(this);
        }
    }

    private int dp(float value) {
        return iOSStyle.dp(this, value);
    }
}