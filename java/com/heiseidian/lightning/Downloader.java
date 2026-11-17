package com.heiseidian.lightning;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Small resume-capable download engine. Task state lives in a json file so downloads survive
 * restarts; running tasks are restored as paused. Status codes reuse DownloadManager constants
 * so the web layer stays simple.
 */
public class Downloader {

    private static Downloader instance;

    public static synchronized Downloader get(Context ctx) {
        if (instance == null) instance = new Downloader(ctx.getApplicationContext());
        return instance;
    }

    public static class Task {
        public long id;
        public String url = "";
        public String name = "";
        public String original = "";
        public String path = "";
        public long soFar;
        public long total = -1;
        public int status = DownloadManager.STATUS_PENDING;
        public String error = "";
        public long time;
        volatile boolean stop;
        volatile HttpURLConnection conn;
        volatile InputStream stream;
    }

    private final Context ctx;
    private final File store;
    private final Object lock = new Object();
    private final List<Task> tasks = new ArrayList<>();
    private final SharedPreferences sp;

    private Downloader(Context c) {
        this.ctx = c;
        this.sp = c.getSharedPreferences("blitz", Context.MODE_PRIVATE);
        this.store = new File(c.getFilesDir(), "tasks.json");
        load();
    }

    private String readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void load() {
        try {
            if (!store.exists()) return;
            String raw = readFile(store);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.id = o.optLong("id");
                t.url = o.optString("url");
                t.name = o.optString("name");
                t.original = o.optString("original");
                t.path = o.optString("path");
                t.soFar = o.optLong("soFar");
                t.total = o.optLong("total", -1);
                t.status = o.optInt("status", DownloadManager.STATUS_PAUSED);
                t.error = o.optString("error");
                t.time = o.optLong("time");
                if (t.status == DownloadManager.STATUS_RUNNING || t.status == DownloadManager.STATUS_PENDING) {
                    t.status = DownloadManager.STATUS_PAUSED;
                }
                tasks.add(t);
            }
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        synchronized (lock) {
            try {
                JSONArray arr = new JSONArray();
                for (Task t : tasks) {
                    JSONObject o = new JSONObject();
                    o.put("id", t.id);
                    o.put("url", t.url);
                    o.put("name", t.name);
                    o.put("original", t.original);
                    o.put("path", t.path);
                    o.put("soFar", t.soFar);
                    o.put("total", t.total);
                    o.put("status", t.status);
                    o.put("error", t.error == null ? "" : t.error);
                    o.put("time", t.time);
                    arr.put(o);
                }
                File tmp = new File(store.getAbsolutePath() + ".tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                fos.write(arr.toString().getBytes("UTF-8"));
                fos.close();
                if (store.exists()) store.delete();
                tmp.renameTo(store);
            } catch (Exception ignored) {
            }
        }
    }

    private long nextId() {
        long id = System.currentTimeMillis();
        synchronized (lock) {
            while (true) {
                boolean used = false;
                for (Task t : tasks) if (t.id == id) used = true;
                if (!used) return id;
                id++;
            }
        }
    }

    private File dir() {
        File d = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (d == null) d = ctx.getFilesDir();
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String uniqueName(String name) {
        File d = dir();
        File file = new File(d, name);
        if (!file.exists()) return name;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (i < 60) {
            String candidate = base + " (" + i + ")" + ext;
            if (!new File(d, candidate).exists()) return candidate;
            i++;
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    public Task byId(long id) {
        synchronized (lock) {
            for (Task t : tasks) if (t.id == id) return t;
        }
        return null;
    }

    public long start(String url, String name, String original) {
        Task t = new Task();
        t.id = nextId();
        t.url = url;
        t.original = original == null ? url : original;
        t.name = uniqueName(name == null || name.isEmpty() ? "download.bin" : name);
        t.path = new File(dir(), t.name).getAbsolutePath();
        t.status = DownloadManager.STATUS_PENDING;
        t.time = System.currentTimeMillis();
        synchronized (lock) {
            tasks.add(t);
        }
        persist();
        startThread(t);
        DownloadService.start(ctx);
        return t.id;
    }

    private void startThread(final Task t) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                download(t);
            }
        }, "blitz-dl-" + t.id);
        th.start();
    }

    private void download(Task t) {
        HttpURLConnection conn = null;
        RandomAccessFile raf = null;
        InputStream in = null;
        File file = new File(t.path);
        long existing = file.exists() ? file.length() : 0;
        try {
            conn = (HttpURLConnection) new URL(t.url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BlackLightning/1.0");
            conn.setRequestProperty("Accept", "*/*");
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
            t.conn = conn;
            int code = conn.getResponseCode();
            if (code == 200) {
                existing = 0;
            } else if (code != 206) {
                throw new IOException("服务器返回 " + code);
            }
            long len = conn.getContentLength();
            long total;
            if (code == 206 && len > 0) total = existing + len;
            else if (len > 0) total = len;
            else total = -1;

            raf = new RandomAccessFile(file, "rw");
            if (existing > 0 && file.length() >= existing) {
                raf.seek(existing);
            } else {
                existing = 0;
                raf.setLength(0);
                raf.seek(0);
            }
            t.soFar = existing;
            t.total = total;
            t.status = DownloadManager.STATUS_RUNNING;
            persist();
            notifyUi();

            in = conn.getInputStream();
            t.stream = in;
            byte[] buf = new byte[8192];
            int n;
            long lastNotify = 0;
            long lastPersist = existing;
            while ((n = in.read(buf)) != -1) {
                if (t.stop) break;
                raf.write(buf, 0, n);
                t.soFar += n;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 450) {
                    lastNotify = now;
                    notifyUi();
                }
                if (t.soFar - lastPersist > 1024 * 1024) {
                    lastPersist = t.soFar;
                    persist();
                }
            }
            if (t.stop) return;
            if (t.total > 0 && t.soFar > t.total) t.soFar = t.total;
            if (t.total < 0) t.total = t.soFar;
            t.status = DownloadManager.STATUS_SUCCESSFUL;
            persist();
            notifyUi();
        } catch (Exception e) {
            if (!t.stop) {
                t.status = DownloadManager.STATUS_FAILED;
                t.error = e.getClass().getSimpleName() + ": " + e.getMessage();
                persist();
                notifyUi();
            }
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
            try {
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {
            }
            t.stream = null;
            t.conn = null;
        }
    }

    public boolean pause(long id) {
        Task t = byId(id);
        if (t == null) return false;
        t.stop = true;
        t.status = DownloadManager.STATUS_PAUSED;
        closeQuietly(t);
        persist();
        notifyUi();
        return true;
    }

    public boolean resume(long id) {
        Task t = byId(id);
        if (t == null) return false;
        if (t.status == DownloadManager.STATUS_SUCCESSFUL) return false;
        t.stop = false;
        t.status = DownloadManager.STATUS_PENDING;
        persist();
        startThread(t);
        DownloadService.start(ctx);
        notifyUi();
        return true;
    }

    public boolean cancel(long id) {
        Task t = byId(id);
        if (t == null) return false;
        t.stop = true;
        closeQuietly(t);
        File f = new File(t.path);
        if (f.exists()) f.delete();
        synchronized (lock) {
            tasks.remove(t);
        }
        persist();
        notifyUi();
        DownloadService.stopIfIdle(ctx);
        return true;
    }

    public boolean delete(long id) {
        Task t = byId(id);
        if (t == null) return false;
        t.stop = true;
        closeQuietly(t);
        File f = new File(t.path);
        if (f.exists()) f.delete();
        synchronized (lock) {
            tasks.remove(t);
        }
        persist();
        notifyUi();
        DownloadService.stopIfIdle(ctx);
        return true;
    }

    private void closeQuietly(Task t) {
        try {
            if (t.stream != null) t.stream.close();
        } catch (Exception ignored) {
        }
        try {
            if (t.conn != null) t.conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    public boolean hasActive() {
        synchronized (lock) {
            for (Task t : tasks) {
                if (t.status == DownloadManager.STATUS_RUNNING || t.status == DownloadManager.STATUS_PENDING) return true;
            }
        }
        return false;
    }

    public Task firstActive() {
        synchronized (lock) {
            for (Task t : tasks) {
                if (t.status == DownloadManager.STATUS_RUNNING || t.status == DownloadManager.STATUS_PENDING) return t;
            }
        }
        return null;
    }

    public int activeCount() {
        int n = 0;
        synchronized (lock) {
            for (Task t : tasks) {
                if (t.status == DownloadManager.STATUS_RUNNING || t.status == DownloadManager.STATUS_PENDING) n++;
            }
        }
        return n;
    }

    public int totalCount() {
        synchronized (lock) {
            return tasks.size();
        }
    }

    public String snapshot() {
        JSONArray items = new JSONArray();
        synchronized (lock) {
            for (Task t : tasks) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", t.id);
                    o.put("title", t.name);
                    o.put("status", t.status);
                    o.put("statusText", statusText(t.status));
                    o.put("soFar", t.soFar);
                    o.put("total", t.total);
                    o.put("percent", t.total > 0 ? (int) (t.soFar * 100 / t.total) : 0);
                    o.put("localUri", "file://" + t.path);
                    o.put("path", t.path);
                    o.put("reason", 0);
                    o.put("error", t.error == null ? "" : t.error);
                    items.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("items", items);
            root.put("deleted", new JSONArray(sp.getString("deleted", "[]")));
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    private String statusText(int status) {
        switch (status) {
            case DownloadManager.STATUS_PENDING:
                return "等待中";
            case DownloadManager.STATUS_RUNNING:
                return "下载中";
            case DownloadManager.STATUS_PAUSED:
                return "已暂停";
            case DownloadManager.STATUS_SUCCESSFUL:
                return "已完成";
            case DownloadManager.STATUS_FAILED:
                return "失败";
            default:
                return "未知";
        }
    }

    public void notifyUi() {
        if (MainActivity.inst != null) {
            MainActivity.inst.js("window.onDlUpdate && window.onDlUpdate()");
        }
    }
}
