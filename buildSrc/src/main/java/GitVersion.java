import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * 自动版本计算插件（纯 Java 实现，原 Kotlin 版因离线环境无法拉取 kotlin-gradle-plugin 改用 Java）。
 *
 * 机制与 FairyXH-Android-Copyright 的 GitVersion.kt 完全一致：
 * - 优先取当前 Git 提交次数作为 versionCode；
 * - 与 version.properties 中历史最高值取 max，保证不回退；
 * - 无 Git 环境时基于历史版本 +1；
 * - 生成 versionName：从 1.0.0 开始，每一位满 10 进位。
 */
public class GitVersion {

    private static final String VERSION_FILE = "version.properties";

    /**
     * 返回 [0]=versionName, [1]=versionCode。
     */
    public static String[] getVersion() {
        int savedVersion = readSavedVersion();
        Integer gitVersion = getGitCommitCount();
        int versionCode;
        if (gitVersion != null) {
            // Git 存在：永远取最高值
            versionCode = Math.max(gitVersion, savedVersion);
        } else {
            // 无 Git：基于历史版本递增
            versionCode = savedVersion + 1;
        }
        saveVersion(versionCode);
        return new String[]{getVersionName(versionCode), String.valueOf(versionCode)};
    }

    /** 获取 Git 提交次数（失败返回 null）。 */
    private static Integer getGitCommitCount() {
        try {
            Process process = new ProcessBuilder("git", "rev-list", "--count", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String out = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .readLine();
            process.waitFor();
            return out == null ? null : Integer.valueOf(out.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取历史最高版本。 */
    private static int readSavedVersion() {
        File file = new File(VERSION_FILE);
        if (!file.exists()) {
            return 0;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return Integer.parseInt(props.getProperty("VERSION_CODE", "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 保存最高版本。 */
    private static void saveVersion(int version) {
        File file = new File(VERSION_FILE);
        Properties props = new Properties();
        props.setProperty("VERSION_CODE", String.valueOf(version));
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "Auto generated version");
        } catch (Exception ignored) {
        }
    }

    /**
     * VersionCode 转 VersionName：
     * 0->1.0.0, 1->1.0.1, 9->1.0.9, 10->1.1.0, 99->1.9.9, 100->2.0.0
     */
    private static String getVersionName(int versionCode) {
        int patch = versionCode % 10;
        int minor = (versionCode / 10) % 10;
        int major = versionCode / 100 + 1;
        return major + "." + minor + "." + patch;
    }
}