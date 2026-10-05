package com.aliya.hy_vq.anime;

import android.content.Context;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 离线缓存（对齐 Kazumi/Animeko 的离线能力）。
 *
 * <h3>设计</h3>
 * <ul>
 *   <li><b>单工作线程串行下载</b>：避免并发打挂小水管源站（与任务队列同思路）；
 *       队列用 {@link LinkedBlockingQueue}，新增任务即时入队。</li>
 *   <li><b>两种形态</b>：
 *       直链（mp4/flv 等）→ 直接落盘；
 *       HLS（m3u8）→ 解析播放列表 → 逐段下载 → <b>顺序拼接为 .ts</b>
 *       （MPEG-TS 包自同步，ExoPlayer 可直接播本地 .ts）。</li>
 *   <li><b>进度</b>：直链按字节，HLS 按已完成段数；每 400ms 回调一次。</li>
 *   <li><b>断点不支持</b>（基础版）：取消即删 .part，重下从头开始。</li>
 * </ul>
 *
 * <h3>存储位置</h3>
 * 应用专属外部目录 {@code files/anime_cache/} —— <b>无需任何存储权限</b>，
 * 卸载即清除，不污染公共相册/下载目录。
 */
public final class OfflineCache {

    /** 单个任务 */
    public static class Task {
        public final String title;        // 显示名（番剧·集）
        public final String url;          // 源地址（m3u8 或直链）
        public final Map headers;         // 请求头（Referer/UA）
        public final String fileName;     // 落盘文件名
        public volatile State state = State.QUEUED;
        public volatile long doneBytes, totalBytes;
        public volatile int doneParts, totalParts;
        public volatile String error;
        public File file;                 // 完成后的文件

        public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

        public Task(String title, String url, Map headers, String fileName) {
            this.title = title == null ? "" : title;
            this.url = url;
            this.headers = headers;
            this.fileName = fileName;
        }

        public int progress() {
            if (state == State.DONE) return 100;
            if (state == State.QUEUED) return 0;
            if (totalParts > 0) return (int) (doneParts * 100L / totalParts);
            if (totalBytes > 0) return (int) (doneBytes * 100L / totalBytes);
            return 0;
        }
    }

    public interface Listener {
        void onChanged();          // 任意任务状态变化（主线程回调）
    }

    private final File root;
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final List<Task> all = new CopyOnWriteArrayList<>();
    private final AtomicBoolean working = new AtomicBoolean(false);
    private volatile Listener listener;
    private Thread worker;
    private final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());

    public OfflineCache(Context ctx) {
        root = new File(ctx.getApplicationContext().getExternalFilesDir(null), "anime_cache");
        if (!root.exists()) root.mkdirs();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public File root() {
        return root;
    }

    public List<Task> tasks() {
        return new ArrayList<>(all);
    }

    /** 已完成的任务 */
    public List<Task> finished() {
        List<Task> out = new ArrayList<>();
        for (Task t : all) if (t.state == Task.State.DONE) out.add(t);
        return out;
    }

    public int activeCount() {
        int n = 0;
        for (Task t : all) if (t.state == Task.State.QUEUED || t.state == Task.State.RUNNING) n++;
        return n;
    }

    /** 入队下载；返回任务（重复 URL 不再入队） */
    public Task enqueue(String title, String url, java.util.Map<String, String> headers) {
        if (url == null || url.isEmpty()) return null;
        for (Task t : all) {
            if (url.equals(t.url) && t.state != Task.State.FAILED && t.state != Task.State.CANCELLED) {
                return t;
            }
        }
        String name = sanitize(title) + "_" + Math.abs(url.hashCode()) + extOf(url);
        Task task = new Task(title, url, headers, name);
        all.add(0, task);
        queue.offer(task);
        kick();
        notifyChanged();
        return task;
    }

    /** 取消任务（运行中则标记，由工作线程在下一个检查点中止） */
    public void cancel(Task t) {
        if (t == null) return;
        if (t.state == Task.State.QUEUED) {
            t.state = Task.State.CANCELLED;
            queue.remove(t);
            notifyChanged();
        } else if (t.state == Task.State.RUNNING) {
            t.state = Task.State.CANCELLED;
        }
    }

    public void remove(Task t) {
        if (t == null) return;
        cancel(t);
        all.remove(t);
        if (t.file != null && t.file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            t.file.delete();
        }
        notifyChanged();
    }

    private void kick() {
        if (working.get()) return;
        worker = new Thread(this::run, "anime-cache-worker");
        worker.setDaemon(true);
        worker.start();
    }

    // ══════════════ 工作线程 ══════════════

    private void run() {
        working.set(true);
        try {
            Task t;
            while ((t = queue.take()) != null) {
                if (t.state == Task.State.CANCELLED) continue;
                t.state = Task.State.RUNNING;
                notifyChanged();
                try {
                    download(t);
                    if (t.state != Task.State.CANCELLED) t.state = Task.State.DONE;
                } catch (Throwable e) {
                    if (t.state != Task.State.CANCELLED) {
                        t.state = Task.State.FAILED;
                        t.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                }
                notifyChanged();
            }
        } catch (InterruptedException ignored) {
        } finally {
            working.set(false);
        }
    }

    private void download(Task t) throws Exception {
        if (t.url.contains(".m3u8")) {
            downloadHls(t);
        } else {
            downloadDirect(t);
        }
    }

    /** 直链下载 */
    private void downloadDirect(Task t) throws Exception {
        HttpURLConnection c = open(t.url, t.headers);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new Exception("HTTP " + code);
        t.totalBytes = c.getContentLength();
        File dst = new File(root, t.fileName + ".part");
        try (InputStream in = c.getInputStream();
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            long last = System.currentTimeMillis(), lastDone = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (t.state == Task.State.CANCELLED) throw new Exception("已取消");
                out.write(buf, 0, n);
                t.doneBytes += n;
                long now = System.currentTimeMillis();
                if (now - last >= 400) {
                    notifyChanged();
                    last = now;
                    lastDone = t.doneBytes;
                }
            }
            out.flush();
        } finally {
            c.disconnect();
        }
        finish(t, dst, extOf(t.url));
    }

    /**
     * HLS：解析主/子播放列表 → 逐段下载 → 顺序拼接为 .ts。
     * MPEG-TS 包 188 字节自同步，ExoPlayer 可直接播放拼接后的本地 .ts。
     */
    private void downloadHls(Task t) throws Exception {
        String playlist = getText(t.url, t.headers);
        if (playlist == null) throw new Exception("播放列表为空");
        List<String> segUrls = parseSegments(playlist, t.url);
        if (segUrls.isEmpty()) throw new Exception("无媒体分段");
        t.totalParts = segUrls.size();

        File dst = new File(root, t.fileName + ".part");
        try (OutputStream out = new FileOutputStream(dst)) {
            for (int i = 0; i < segUrls.size(); i++) {
                if (t.state == Task.State.CANCELLED) throw new Exception("已取消");
                // 分片加密（AES-128）——基础版不支持，直接报错比下载损坏文件更好
                if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
                    throw new Exception("分片已加密（AES），暂不支持离线缓存");
                }
                HttpURLConnection c = open(segUrls.get(i), t.headers);
                int code = c.getResponseCode();
                if (code / 100 != 2) {
                    c.disconnect();
                    throw new Exception("分段 HTTP " + code);
                }
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (t.state == Task.State.CANCELLED) throw new Exception("已取消");
                        out.write(buf, 0, n);
                        t.doneBytes += n;
                    }
                } finally {
                    c.disconnect();
                }
                t.doneParts = i + 1;
                notifyChanged();
            }
            out.flush();
        }
        finish(t, dst, ".ts");
    }

    private void finish(Task t, File part, String ext) throws Exception {
        File dst = new File(root, t.fileName);
        if (!dst.getName().endsWith(ext)) {
            dst = new File(root, stripExt(t.fileName) + ext);
        }
        if (dst.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        if (!part.renameTo(dst)) {
            throw new Exception("无法写入目标文件");
        }
        t.file = dst;
    }

    // ══════════════ 工具 ══════════════

    /** 解析 m3u8 分段（含主清单 → 子清单一层嵌套） */
    private List<String> parseSegments(String playlist, String baseUrl) throws Exception {
        List<String> out = new ArrayList<>();
        boolean isMaster = playlist.contains("#EXT-X-STREAM-INF");
        if (isMaster) {
            // 主清单：取第一条子清单
            String uri = firstUri(playlist);
            if (uri == null || uri.isEmpty()) return out;
            String sub = getText(absolute(baseUrl, uri), null);
            if (sub == null || sub.isEmpty()) return out;
            playlist = sub;
            baseUrl = absolute(baseUrl, uri);
        }
        for (String line : playlist.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            out.add(absolute(baseUrl, l));
        }
        return out;
    }

    private String firstUri(String playlist) {
        for (String line : playlist.split("\\r?\\n")) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("#")) return l;
        }
        return null;
    }

    private String absolute(String base, String ref) {
        if (ref == null) return null;
        if (ref.startsWith("http")) return ref;
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Throwable t) {
            return ref;
        }
    }

    private HttpURLConnection open(String url, Map headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
        if (headers != null) {
            for (Object e : headers.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                if (en.getKey() != null && en.getValue() != null) {
                    c.setRequestProperty(String.valueOf(en.getKey()), String.valueOf(en.getValue()));
                }
            }
        }
        return c;
    }

    private String getText(String url, Map headers) throws Exception {
        HttpURLConnection c = open(url, headers);
        try {
            if (c.getResponseCode() / 100 != 2) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(),
                    Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String extOf(String url) {
        String p = url.split("\\?")[0].toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".m3u8")) return ".ts";
        int i = p.lastIndexOf('.');
        if (i > 0 && p.length() - i <= 5) return p.substring(i);
        return ".mp4";
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "anime";
        return s.replaceAll("[\\\\/:*?\"<>|\\\\s]+", "_").replaceAll("^_+|_+$", "");
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) main.post(l::onChanged);
    }
}
