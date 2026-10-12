package com.fairyxh.multiprojection.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fairyxh.multiprojection.R;
import com.fairyxh.multiprojection.TemplateConfig;

/** Simple launcher screen so the APK is easy to identify after install. */
public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView text = new TextView(this);
        text.setTextColor(Color.rgb(230, 230, 240));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setGravity(Gravity.START);
        text.setPadding(dp(18), dp(18), dp(18), dp(18));
        text.setLinksClickable(true);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        text.setText(getString(R.string.launcher_instructions, getString(R.string.app_name), firstTarget()));

        Button rulesBtn = new Button(this);
        rulesBtn.setText("自动授权规则");
        rulesBtn.setOnClickListener(v ->
                startActivity(new Intent(this, RulesActivity.class)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.rgb(14, 14, 20));
        content.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(rulesBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private static String firstTarget() {
        return TemplateConfig.TARGET_PACKAGES.length == 0
                ? "<none configured>" : TemplateConfig.TARGET_PACKAGES[0];
    }
}
