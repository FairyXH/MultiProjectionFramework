package com.fairyxh.multiprojection.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fairyxh.multiprojection.rule.RulesConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则 Tab：自动授权规则管理（iOS 风格分组卡片）。
 * 支持添加 / 删除规则，规则保存到 App 私有目录并同步到 system_server 可读路径。
 */
public final class RulesTab {
    private static final int[] MODE_VALUES = {
            RulesConfig.MODE_FULL_SCREEN,
            RulesConfig.MODE_SINGLE_SELF,
            RulesConfig.MODE_SINGLE_TARGET
    };
    private static final String[] MODE_LABELS = {"整屏", "单应用-自身", "单应用-自定义"};

    private final Context ctx;
    private LinearLayout listContainer;

    public RulesTab(Context ctx) {
        this.ctx = ctx;
    }

    public LinearLayout build() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(iOSStyle.BG);

        // 提示卡
        root.addView(iOSStyle.sectionTitle(ctx, "自动授权规则"));
        LinearLayout hintCard = iOSStyle.card(ctx);
        TextView hint = iOSStyle.cellDetail(ctx,
                "规则内 App 发起录屏 / 投屏时自动授权、不弹窗。\n"
                        + "保存后需重启设备（system_server）生效。");
        hint.setLineSpacing(0, 1.2f);
        int padV = iOSStyle.dp(ctx, 10);
        hint.setPadding(0, padV, 0, padV);
        iOSStyle.addCell(hintCard, hint, true);
        root.addView(hintCard);

        // 添加按钮（iOS 蓝色大按钮）
        TextView addBtn = bigBlueButton(ctx, "＋ 添加规则");
        addBtn.setOnClickListener(v -> showAddDialog());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, iOSStyle.dp(ctx, 48));
        btnLp.setMargins(iOSStyle.dp(ctx, 16), 0, iOSStyle.dp(ctx, 16), iOSStyle.dp(ctx, 14));
        root.addView(addBtn, btnLp);

        // 规则列表
        root.addView(iOSStyle.sectionTitle(ctx, "已配置规则"));
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        refreshList();

        // 底部留白
        TextView footer = new TextView(ctx);
        footer.setMinimumHeight(iOSStyle.dp(ctx, 40));
        root.addView(footer);
        return root;
    }

    private void refreshList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        List<RulesConfig.Rule> rules = RulesConfig.load(ctx.getFilesDir());
        if (rules.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无规则\n（不配置规则时，本模块不影响任何 App）");
            empty.setTextColor(iOSStyle.TEXT_SECONDARY);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, iOSStyle.dp(ctx, 20), 0, iOSStyle.dp(ctx, 20));
            empty.setBackground(iOSStyle.cardBg(ctx, 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(iOSStyle.dp(ctx, 16), 0, iOSStyle.dp(ctx, 16), 0);
            listContainer.addView(empty, lp);
            return;
        }
        for (RulesConfig.Rule r : rules) {
            listContainer.addView(buildRuleCard(r));
        }
    }

    private View buildRuleCard(final RulesConfig.Rule rule) {
        LinearLayout card = iOSStyle.card(ctx);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.VERTICAL);
        int padV = iOSStyle.dp(ctx, 10);
        header.setPadding(0, padV, 0, 0);

        TextView pkg = iOSStyle.cellTitle(ctx, rule.appPkg);
        pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pkg.setTypeface(pkg.getTypeface(), Typeface.BOLD);
        header.addView(pkg);

        TextView mode = iOSStyle.cellDetail(ctx, describe(rule));
        mode.setPadding(0, iOSStyle.dp(ctx, 3), 0, 0);
        header.addView(mode);
        iOSStyle.addCell(card, header, false);

        TextView del = new TextView(ctx);
        del.setText("删除");
        del.setTextColor(iOSStyle.DANGER);
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        del.setGravity(Gravity.CENTER);
        del.setPadding(0, iOSStyle.dp(ctx, 12), 0, iOSStyle.dp(ctx, 12));
        del.setOnClickListener(v -> {
            List<RulesConfig.Rule> all = RulesConfig.load(ctx.getFilesDir());
            List<RulesConfig.Rule> next = new ArrayList<>(all);
            next.remove(rule);
            persistRules(next);
            refreshList();
        });
        iOSStyle.addCell(card, del, true);
        return card;
    }

    private String describe(RulesConfig.Rule r) {
        String label = MODE_LABELS[0];
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i] == r.mode) {
                label = MODE_LABELS[i];
                break;
            }
        }
        String extra = r.mode == RulesConfig.MODE_SINGLE_TARGET
                ? (r.targetPkg == null ? "" : " → " + r.targetPkg) : "";
        return "录制范围：" + label + extra;
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(iOSStyle.dp(ctx, 20), iOSStyle.dp(ctx, 10), iOSStyle.dp(ctx, 20), 0);

        final EditText pkgInput = new EditText(ctx);
        pkgInput.setHint("申请包名（发起录屏的 App）");
        pkgInput.setSingleLine(true);
        form.addView(pkgInput);

        final EditText targetInput = new EditText(ctx);
        targetInput.setHint("目标包名（仅单应用-自定义时填写）");
        targetInput.setSingleLine(true);
        form.addView(targetInput);

        final int[] mode = {RulesConfig.MODE_FULL_SCREEN};
        final TextView modeBtn = new TextView(ctx);
        modeBtn.setText("录制范围：整屏");
        modeBtn.setTextColor(iOSStyle.ACCENT);
        modeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        modeBtn.setGravity(Gravity.CENTER);
        modeBtn.setPadding(0, iOSStyle.dp(ctx, 12), 0, iOSStyle.dp(ctx, 12));
        modeBtn.setOnClickListener(v -> {
            mode[0] = MODE_VALUES[(mode[0] + 1) % MODE_VALUES.length];
            modeBtn.setText("录制范围：" + labelOf(mode[0]));
        });
        form.addView(modeBtn);

        new AlertDialog.Builder(ctx)
                .setTitle("添加规则")
                .setMessage("提示：查不到应用包名时，可在「设置-应用」详情页查看，或用 APK 分析工具获取。")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String pkg = pkgInput.getText().toString().trim();
                    if (pkg.isEmpty()) return;
                    String target = mode[0] == RulesConfig.MODE_SINGLE_TARGET
                            ? targetInput.getText().toString().trim() : null;
                    if (mode[0] == RulesConfig.MODE_SINGLE_TARGET
                            && (target == null || target.isEmpty())) return;
                    List<RulesConfig.Rule> all = RulesConfig.load(ctx.getFilesDir());
                    all.add(new RulesConfig.Rule(pkg, mode[0], target));
                    persistRules(all);
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String labelOf(int mode) {
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i] == mode) return MODE_LABELS[i];
        }
        return MODE_LABELS[0];
    }

    private void persistRules(List<RulesConfig.Rule> rules) {
        if (RulesConfig.save(ctx.getFilesDir(), rules)) {
            RulesConfig.syncToSystemPath(RulesConfig.toJson(rules));
        }
    }

    /** iOS 风格蓝色大按钮（圆角 12dp 实心）。 */
    private static TextView bigBlueButton(Context c, String text) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(android.graphics.Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(iOSStyle.ACCENT);
        g.setCornerRadius(iOSStyle.dp(c, 12));
        b.setBackground(g);
        return b;
    }
}