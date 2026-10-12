package com.fairyxh.multiprojection.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fairyxh.multiprojection.rule.RulesConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动授权规则配置页。
 *
 * <p>列出当前规则（申请包名 + 模式），支持新增/删除。规则保存到模块 App 的
 * files/rules.json（system_server 有 root 权限直接读取）。</p>
 *
 * <p>模式说明：整屏 = 规则内 App 自动授权并录制整个屏幕；单应用-自身 = 只录制该 App
 * 自己的窗口；单应用-自定义 = 录制指定的其他应用窗口（目标包名需为前台任务）。</p>
 */
public final class RulesActivity extends Activity {
    private static final int[] MODE_VALUES = {
            RulesConfig.MODE_FULL_SCREEN,
            RulesConfig.MODE_SINGLE_SELF,
            RulesConfig.MODE_SINGLE_TARGET
    };
    private static final String[] MODE_LABELS = {"整屏", "单应用-自身", "单应用-自定义"};

    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshList();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(14, 14, 20));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("自动授权规则");
        title.setTextColor(Color.rgb(230, 230, 240));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("规则内 App 发起录屏/投屏时自动授权、不弹窗。\n保存后需重启 system_server 生效（重启设备）。");
        hint.setTextColor(Color.rgb(150, 160, 180));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, 0, 0, dp(10));
        root.addView(hint);

        Button add = new Button(this);
        add.setText("＋ 添加规则");
        add.setOnClickListener(v -> showAddDialog());
        root.addView(add);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void refreshList() {
        listContainer.removeAllViews();
        List<RulesConfig.Rule> rules = RulesConfig.load(getFilesDir());
        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无规则）");
            empty.setTextColor(Color.rgb(120, 130, 150));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(0, dp(10), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (RulesConfig.Rule r : rules) {
            listContainer.addView(buildRuleRow(r, rules));
        }
    }

    private View buildRuleRow(final RulesConfig.Rule rule, final List<RulesConfig.Rule> all) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView info = new TextView(this);
        info.setText(describe(rule));
        info.setTextColor(Color.rgb(220, 220, 230));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(info);

        Button del = new Button(this);
        del.setText("删除");
        del.setOnClickListener(v -> {
            List<RulesConfig.Rule> next = new ArrayList<>(all);
            next.remove(rule);
            RulesConfig.save(getFilesDir(), next);
            refreshList();
        });
        row.addView(del);
        return row;
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
        return r.appPkg + "  [" + label + "]" + extra;
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), 0);

        final EditText pkgInput = new EditText(this);
        pkgInput.setHint("申请包名（发起录屏的 App）");
        pkgInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        form.addView(pkgInput);

        final EditText targetInput = new EditText(this);
        targetInput.setHint("目标包名（仅单应用-自定义时填写）");
        targetInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        form.addView(targetInput);

        final int[] mode = {RulesConfig.MODE_FULL_SCREEN};
        Button modeBtn = new Button(this);
        modeBtn.setText("模式：整屏");
        modeBtn.setOnClickListener(v -> {
            mode[0] = MODE_VALUES[(mode[0] + 1) % MODE_VALUES.length];
            modeBtn.setText("模式：" + MODE_LABELS[0]);
            for (int i = 0; i < MODE_VALUES.length; i++) {
                if (MODE_VALUES[i] == mode[0]) {
                    modeBtn.setText("模式：" + MODE_LABELS[i]);
                    break;
                }
            }
        });
        form.addView(modeBtn);

        new AlertDialog.Builder(this)
                .setTitle("添加规则")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String pkg = pkgInput.getText().toString().trim();
                    if (pkg.isEmpty()) return;
                    String target = mode[0] == RulesConfig.MODE_SINGLE_TARGET
                            ? targetInput.getText().toString().trim() : null;
                    if (mode[0] == RulesConfig.MODE_SINGLE_TARGET && (target == null || target.isEmpty())) {
                        return;
                    }
                    List<RulesConfig.Rule> all = RulesConfig.load(getFilesDir());
                    all.add(new RulesConfig.Rule(pkg, mode[0], target));
                    RulesConfig.save(getFilesDir(), all);
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}