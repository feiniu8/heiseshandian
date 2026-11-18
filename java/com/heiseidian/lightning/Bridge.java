package com.heiseidian.lightning;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

public class Bridge {

    private final MainActivity ctx;
    private final WebView web;
    private final SharedPreferences sp;
    private final Downloader engine;

    Bridge(MainActivity activity, WebView view) {
        this.ctx = activity;
        this.web = view;
        this.sp = activity.getSharedPreferences("blitz", Context.MODE_PRIVATE);
        this.engine = Downloader.get(activity);
    }

    private JSONArray tombstones() {
        try {
            return new JSONArray(sp.getString("deleted", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void addTombstone(String title, String reason) {
        try {
            JSONArray arr = tombstones();
            JSONObject o = new JSONObject();
            o.put("title", title == null ? "未知文件" : title);
            o.put("reason", reason);
            o.put("time", System.currentTimeMillis());
            arr.put(o);
            JSONArray kept = new JSONArray();
            int start = Math.max(0, arr.length() - 60);
            for (int i = start; i < arr.length(); i++) kept.put(arr.get(i));
            sp.edit().putString("deleted", kept.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private String safeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) return "download.bin";
        name = name.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_");
        if (name.length() > 140) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot < 12) ext = name.substring(dot);
            name = name.substring(0, 130) + ext;
        }
        return name;
    }

    @JavascriptInterface
    public long download(String url, String fileName, String originalUrl) {
        try {
            if (url == null || !url.startsWith("http")) return -1;
            return engine.start(url, safeName(fileName), originalUrl);
        } catch (Exception e) {
            return -1;
        }
    }

    @JavascriptInterface
    public String listDownloads() {
        try {
            return engine.snapshot();
        } catch (Exception e) {
            return "{\"items\":[],\"deleted\":[]}";
        }
    }

    @JavascriptInterface
    public boolean pause(long id) {
        return engine.pause(id);
    }

    @JavascriptInterface
    public boolean resume(long id) {
        return engine.resume(id);
    }

    @JavascriptInterface
    public boolean cancel(long id) {
        try {
            Downloader.Task t = engine.byId(id);
            boolean ok = engine.cancel(id);
            addTombstone(t != null ? t.name : "下载任务", "已取消");
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean deleteFile(long id) {
        try {
            Downloader.Task t = engine.byId(id);
            boolean ok = engine.delete(id);
            addTombstone(t != null ? t.name : "下载文件", "已删除");
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean clearDeleted() {
        sp.edit().putString("deleted", "[]").apply();
        return true;
    }

    private String pathOf(long id) {
        Downloader.Task t = engine.byId(id);
        return t == null ? null : t.path;
    }

    @JavascriptInterface
    public String install(long id) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                return "need_permission";
            }
            String path = pathOf(id);
            if (path == null) return "missing";
            File file = new File(path);
            if (!file.exists()) return "missing";
            Uri uri = FilesProvider.uriFor(ctx, file.getName());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return "ok";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    @JavascriptInterface
    public String openFile(long id) {
        try {
            String path = pathOf(id);
            if (path == null) return "missing";
            File file = new File(path);
            if (!file.exists()) return "missing";
            Uri uri = FilesProvider.uriFor(ctx, file.getName());
            String type = ctx.getContentResolver().getType(uri);
            if (type == null) type = "application/octet-stream";
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, type);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return "ok";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    @JavascriptInterface
    public boolean isInstallAllowed() {
        try {
            return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return true;
        }
    }

    @JavascriptInterface
    public void ping(final String url, final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                boolean ok = false;
                int code = 0;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(7000);
                    conn.setRequestMethod("HEAD");
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "BlackLightning/1.0");
                    code = conn.getResponseCode();
                    ok = code > 0 && code < 400;
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                long ms = ok ? (System.currentTimeMillis() - start) : -1;
                String js = "window.onPing && window.onPing(" + JSONObject.quote(key) + "," + ms + ","
                        + ok + "," + code + ")";
                if (MainActivity.inst != null) MainActivity.inst.js(js);
            }
        }).start();
    }

    @JavascriptInterface
    public void inAppOpen(String url) {
        try {
            ctx.openInAppUrl(url);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void openExternal(String url) {
        try {
            ctx.openExternalUrl(url);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void toast(final String message) {
        ctx.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public int versionCode() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    @JavascriptInterface
    public String downloadDir() {
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? "" : dir.getAbsolutePath();
    }

    @JavascriptInterface
    public void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("blacklightning", text));
            toast("已复制");
        } catch (Exception ignored) {
        }
    }

    /** 「我」页上传：拉起系统文件选择器，结果通过 window.onPickFile(name, mime, base64, size) 回传 */
    @JavascriptInterface
    public void pickLocalFile() {
        ctx.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ctx.pickFileForUpload();
            }
        });
    }
}
