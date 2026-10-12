package com.fairyxh.multiprojection.rule;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动授权规则模型 + JSON 持久化。
 *
 * <p>规则结构：{@code appPkg}（申请授权的应用包名）、{@code mode}（录制范围模式）、
 * {@code targetPkg}（单应用-自定义模式下的目标可录制包名；自身模式为 null）。</p>
 *
 * <p>模式：{@link #MODE_FULL_SCREEN} 整屏 / {@link #MODE_SINGLE_SELF} 单应用-自身 /
 * {@link #MODE_SINGLE_TARGET} 单应用-自定义目标包名。</p>
 *
 * <p>文件存放在模块 App 的 files 目录（system_server 有 root 权限可读），文件名为
 * {@code rules.json}。读写全部使用 UTF-8；解析失败时返回空列表（不影响系统功能）。</p>
 */
public final class RulesConfig {
    /** 整屏录制。 */
    public static final int MODE_FULL_SCREEN = 0;
    /** 单个应用：只录申请方自身（它自己的包名）。 */
    public static final int MODE_SINGLE_SELF = 1;
    /** 单个应用：自定义可录制的目标包名。 */
    public static final int MODE_SINGLE_TARGET = 2;

    private static final String FILE_NAME = "rules.json";
    private static final String TAG = "MPFw-Rules";

    public static final class Rule {
        /** 申请授权的应用包名（规则命中条件）。 */
        public final String appPkg;
        /** 录制范围模式：MODE_FULL_SCREEN / MODE_SINGLE_SELF / MODE_SINGLE_TARGET。 */
        public final int mode;
        /** 单应用-自定义模式下要录制的目标包名；其他模式为 null。 */
        public final String targetPkg;

        public Rule(String appPkg, int mode, String targetPkg) {
            this.appPkg = appPkg;
            this.mode = mode;
            this.targetPkg = targetPkg;
        }
    }

    private RulesConfig() {}

    /** 读取规则列表；文件不存在或解析失败返回空列表。 */
    public static List<Rule> load(File dir) {
        try {
            File f = new File(dir, FILE_NAME);
            if (!f.exists() || !f.isFile()) return new ArrayList<>(0);
            byte[] bytes;
            try (FileInputStream in = new FileInputStream(f)) {
                bytes = new byte[(int) f.length()];
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "load rules failed: " + t);
            return new ArrayList<>(0);
        }
    }

    /** 从 JSON 字符串解析规则列表（供 system_server 经 Provider 读取后解析）；异常返回空列表。 */
    public static List<Rule> parse(String json) {
        try {
            if (json == null || json.trim().isEmpty()) return new ArrayList<>(0);
            JSONArray arr = new JSONArray(json);
            List<Rule> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String appPkg = o.optString("appPkg");
                if (appPkg == null || appPkg.isEmpty()) continue;
                int mode = o.optInt("mode", MODE_FULL_SCREEN);
                String target = o.has("targetPkg") && !o.isNull("targetPkg") ? o.getString("targetPkg") : null;
                out.add(new Rule(appPkg, mode, target));
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "parse rules failed: " + t);
            return new ArrayList<>(0);
        }
    }

    /** 保存规则列表（原子写：先写临时文件再 rename）。 */
    public static boolean save(File dir, List<Rule> rules) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "mkdir failed: " + dir);
                return false;
            }
            String json = toJson(rules);
            File f = new File(dir, FILE_NAME);
            File tmp = new File(dir, FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "delete old rules failed: " + f);
            }
            if (!tmp.renameTo(f)) {
                Log.w(TAG, "rename rules failed");
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "save rules failed: " + t);
            return false;
        }
    }

    /** 将规则列表转成 JSON 数组字符串。 */
    public static String toJson(List<Rule> rules) {
        JSONArray arr = new JSONArray();
        if (rules != null) {
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                try {
                    o.put("appPkg", r.appPkg);
                    o.put("mode", r.mode);
                    if (r.targetPkg != null) o.put("targetPkg", r.targetPkg);
                } catch (Throwable ignored) {
                }
                arr.put(o);
            }
        }
        return arr.toString();
    }
/** 查找申请方对应的规则；无则返回 null。 */
    public static Rule findFor(List<Rule> rules, String appPkg) {
        if (rules == null || appPkg == null) return null;
        for (Rule r : rules) {
            if (appPkg.equals(r.appPkg)) return r;
        }
        return null;
    }

    /**
     * 将规则 JSON 同步到 system_server 可读的全局路径（root 写入）。
     *
     * <p>模块 App 自身无权限写 /data/misc/mpfw/，必须经 su(root) 执行。
     * 若设备未授权 App 的 su 调用，此同步静默失败（规则仍保存在 App 私有目录，
     * 经 ContentProvider 兜底，但需要 App 进程存活）。</p>
     */
    public static void syncToSystemPath(String rulesJson) {
        try {
            if (rulesJson == null) return;
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "mkdir -p /data/misc/mpfw && chmod 755 /data/misc/mpfw && "
                            + "echo '" + rulesJson.replace("'", "'\\''") + "' > /data/misc/mpfw/rules.json && "
                            + "chmod 644 /data/misc/mpfw/rules.json"
            });
            int code = p.waitFor();
            if (code != 0) {
                Log.w(TAG, "syncToSystemPath su failed: " + code);
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncToSystemPath failed: " + t);
        }
    }

    /** 单应用规则的实际录制目标包名：自身=appPkg，自定义=targetPkg（可为 null）。 */
    public static String effectiveTargetPkg(Rule rule) {
        if (rule == null) return null;
        if (rule.mode == MODE_SINGLE_TARGET) return rule.targetPkg;
        return rule.appPkg;
    }
}
