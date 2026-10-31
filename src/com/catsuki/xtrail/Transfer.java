package com.catsuki.xtrail;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Export / import of the footprint history as a plain text file (Downloads folder, system file picker). */
final class Transfer {
    static final int REQ_IMPORT = 0x58545231;
    private static final String MAGIC = "XTRAIL-HISTORY 1";

    private static void toast(final Activity a, final String s) {
        a.runOnUiThread(() -> Toast.makeText(a, s, Toast.LENGTH_LONG).show());
    }

    static void export(final Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { toast(a, "此系统版本不支持导出"); return; }
        new Thread(() -> {
            try {
                String body = NativeTab.exportText();
                if (body == null) { toast(a, "暂无足迹可导出"); return; }
                String name = "xtrail-history-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                ContentResolver cr = a.getContentResolver();
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IllegalStateException("无法创建文件");
                try (OutputStream o = cr.openOutputStream(u)) {
                    o.write((MAGIC + "\n" + body).getBytes(StandardCharsets.UTF_8));
                }
                toast(a, "已导出到「下载」/" + name);
            } catch (Throwable t) {
                toast(a, "导出失败：" + t.getMessage());
            }
        }, "xtrail-export").start();
    }

    /** opens the system file picker; the result comes back through {@link #onResult} */
    static void pickImport(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            a.startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            toast(a, "无法打开文件选择器：" + t.getMessage());
        }
    }

    static void onResult(final Activity a, final Intent data) {
        final Uri u = data == null ? null : data.getData();
        if (u == null) return;
        new Thread(() -> {
            try (InputStream in = a.getContentResolver().openInputStream(u)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > 64 * 1024 * 1024) throw new IllegalStateException("文件过大");
                }
                String text = o.toString("UTF-8");
                if (!text.startsWith(MAGIC)) { toast(a, "不是 XTrail 导出的文件"); return; }
                int n = NativeTab.importText(text.substring(MAGIC.length()));
                toast(a, n < 0 ? "导入失败" : "已导入 " + n + " 条足迹");
            } catch (Throwable t) {
                toast(a, "导入失败：" + t.getMessage());
            }
        }, "xtrail-import").start();
    }
}
