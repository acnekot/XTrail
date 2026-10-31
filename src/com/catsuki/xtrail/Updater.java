package com.catsuki.xtrail;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks the latest GitHub release of the module; runs inside X, so it only needs X's own network access. */
final class Updater {
    /** keep in step with versionName in AndroidManifest.xml */
    static final String VERSION = "0.5.1";
    private static final String API = "https://api.github.com/repos/acnekot/XTrail/releases/latest";

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\n", "\n").replace("\\r", "").replace("\\/", "/").replace("\\\"", "\"") : null;
    }

    /** true when remote is a higher dotted version than local (non-numeric parts ignored) */
    static boolean newer(String remote, String local) {
        String[] r = remote.replaceFirst("^[vV]", "").split("\\."), l = local.split("\\.");
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int x = i < r.length ? num(r[i]) : 0, y = i < l.length ? num(l[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        Matcher m = Pattern.compile("\\d+").matcher(s);
        try { return m.find() ? Integer.parseInt(m.group()) : 0; } catch (NumberFormatException e) { return 0; }
    }

    private static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url != null ? url : "https://github.com/acnekot/XTrail/releases")));
        } catch (Throwable t) {
            Toast.makeText(a, "无法打开浏览器", Toast.LENGTH_LONG).show();
        }
    }

    static void check(final Activity a) {
        Toast.makeText(a, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String json = null, err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "XTrail/" + VERSION);
                if (c.getResponseCode() != 200) err = "服务器返回 " + c.getResponseCode();
                else try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                    json = o.toString("UTF-8");
                }
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            final String j = json, e = err;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (j == null) {
                    new AlertDialog.Builder(a)
                            .setTitle("检查更新失败")
                            .setMessage(e + "\n\nX 当前的网络可能无法直接访问 GitHub，可以在浏览器里查看最新版本。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("打开发布页", (d, w) -> open(a, null))
                            .show();
                    return;
                }
                final String tag = field(j, "tag_name"), url = field(j, "html_url");
                if (tag == null || !newer(tag, VERSION)) {
                    Toast.makeText(a, "已是最新版本（" + VERSION + "）", Toast.LENGTH_LONG).show();
                    return;
                }
                String notes = field(j, "body");
                new AlertDialog.Builder(a)
                        .setTitle("发现新版本 " + tag)
                        .setMessage("当前版本 " + VERSION + (notes == null || notes.isEmpty() ? "" : "\n\n" + notes))
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("前往下载", (d, w) -> {
                            open(a, url);
                        })
                        .show();
            });
        }, "xtrail-update").start();
    }
}
