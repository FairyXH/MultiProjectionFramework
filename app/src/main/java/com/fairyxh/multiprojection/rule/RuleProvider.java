package com.fairyxh.multiprojection.rule;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 规则文件读取 Provider（system_server → 模块 App 的 Binder 桥）。
 *
 * <p><b>为什么需要 Provider：</b>system_server 进程以 uid=1000(system) 运行，不是 root，
 * 无法读取模块 App 私有数据目录（/data/user/0/...，owner 是 App 的 uid，0600）。因此
 * system_server 不能直接读 rules.json 文件，必须通过 ContentProvider 桥：system_server 调
 * content://com.fairyxh.multiprojection.rule/rules，模块 App 进程（有权读自己的文件）读取
 * 并返回 JSON 字符串。</p>
 *
 * <p>安全：内容只是包名+模式列表（不敏感）。exported=true 以便 system_server 调用；
 * 不做写操作，仅只读 query。</p>
 */
public final class RuleProvider extends ContentProvider {
    private static final String TAG = "MPFw-RuleProvider";
    /** authority，与 Manifest 中 android:authorities 一致。 */
    public static final String AUTHORITY = "com.fairyxh.multiprojection.rule";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/rules");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        try {
            File f = new File(getContext().getFilesDir(), "rules.json");
            if (!f.exists()) return new MatrixCursor(new String[]{"json"});
            byte[] bytes;
            try (FileInputStream in = new FileInputStream(f)) {
                bytes = new byte[(int) f.length()];
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            MatrixCursor c = new MatrixCursor(new String[]{"json"});
            c.addRow(new Object[]{json});
            return c;
        } catch (Throwable t) {
            Log.w(TAG, "query failed: " + t);
            // 返回空结果而非抛异常，保证 system_server 侧调用稳定
            return new MatrixCursor(new String[]{"json"});
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/json";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}