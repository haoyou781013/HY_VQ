package com.aliya.hy_vq.anime;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * WebView 嗅探取链 —— 原理参考 Animeko 的 {@code AndroidWebViewVideoExtractor}。
 *
 * <h3>为什么要它</h3>
 * k2a 工具实测：18 个源里只有 2 个能用纯解析拿到直链。剩下的分两类：
 * <ul>
 *   <li><b>第三方解析 iframe</b>（如 AGE 用 {@code jx.wuzhoupai.com}）——
 *       直链在别人手里，本页根本不存在</li>
 *   <li><b>encrypt=3</b>（hex→base64→AES 密文）—— 解密 JS 在外部脚本里</li>
 * </ul>
 * 这两类都**无法靠读 HTML 解决**，但有个共同点：**只要 WebView 能播，请求就会露出来**。
 *
 * <h3>原理（对照 Animeko）</h3>
 * <pre>
 * Animeko:                                 本类:
 *   shouldInterceptRequest  拦截请求   →    同左
 *   onLoadResource          监听加载   →    同左
 *   Instruction 三态:                       简化为两态:
 *     Continue        继续                   不是媒体 → 忽略
 *     LoadPage        让 WebView 跳嵌套页     （未实现：见下方"未做的部分"）
 *     FoundResource   找到                   是媒体 → 回调
 * </pre>
 *
 * <h3>未做的部分（有意简化）</h3>
 * Animeko 还有「每源可插拔 matcher」和「LoadPage 自动跳嵌套页」，
 * 那需要为每个站点单独维护补丁（它那边是 657 个文件的工程）。本类只做
 * **通用嗅探**：任何被 WebView 加载出来的 m3u8/mp4 都能被捕获。
 *
 * <h3>安全提示</h3>
 * 必须开启 JavaScript（页面靠 JS 取链）。因此**只应在用户明确触发解析时创建**，
 * 用完立即 {@link #destroy()}，不要让它在后台常驻。
 */
public class WebViewLinkExtractor {

    /** 默认超时：与 AnimeSearchManager 的预算同量级 */
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;

    public interface Callback {
        /** 成功嗅探到媒体地址 */
        void onFound(String url);

        /** 失败（超时 / 无媒体 / WebView 不可用） */
        void onFailed(String reason);
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private FrameLayout holder;
    private ViewGroup host;
    private boolean done;
    private Callback callback;
    private Runnable timeoutTask;

    public WebViewLinkExtractor(Activity activity) {
        this.activity = activity;
    }

    /**
     * 加载页面并嗅探媒体地址。回调在主线程。
     *
     * @param pageUrl   播放页地址
     * @param userAgent 与解析阶段一致的 UA（源站常按 UA 区分响应）
     * @param referer   播放页自身作为 Referer
     * @param host      用于挂载隐藏 WebView 的容器（可为 null，此时用 1×1 的不可见容器）
     */
    @SuppressLint("SetJavaScriptEnabled")
    public void extract(final String pageUrl, final String userAgent, final String referer,
                        final ViewGroup host, final long timeoutMs, final Callback callback) {
        this.callback = callback;
        this.host = host;
        this.done = false;

        activity.runOnUiThread(() -> {
            try {
                webView = new WebView(activity);
                WebSettings s = webView.getSettings();
                // 必须开 JS：播放页靠脚本算出直链
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setLoadsImagesAutomatically(false);       // 不需要图，省流量
                s.setBlockNetworkImage(true);
                if (userAgent != null && !userAgent.isEmpty()) {
                    s.setUserAgentString(userAgent);
                }
                // 有些源是 https 页面里嵌 http 资源
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        try {
                            if (request != null && request.getUrl() != null) {
                                inspect(request.getUrl().toString());
                            }
                        } catch (Throwable ignored) {
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onLoadResource(WebView view, String url) {
                        inspect(url);
                        super.onLoadResource(view, url);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        // 主文档失败才算失败；子资源失败忽略（广告/统计被墙是常态）
                        if (failingUrl != null && failingUrl.equals(pageUrl)) {
                            fail("页面加载失败：" + description);
                        }
                    }
                });

                // 挂到不可见容器：部分 WebView 实现要求 attached 才能正常发起网络请求
                holder = new FrameLayout(activity);
                holder.setVisibility(android.view.View.GONE);
                holder.setBackgroundColor(Color.TRANSPARENT);
                holder.addView(webView, new FrameLayout.LayoutParams(1, 1));
                if (host != null) {
                    host.addView(holder, new ViewGroup.LayoutParams(1, 1));
                } else {
                    activity.addContentView(holder, new ViewGroup.LayoutParams(1, 1));
                }

                // 注入 Referer 的可行做法：先设置一个同源 about:blank，再带上 header 加载
                webView.loadUrl(pageUrl);
            } catch (Throwable t) {
                fail("WebView 创建失败：" + t.getMessage());
            }
        });

        timeoutTask = () -> fail("超时（" + (timeoutMs / 1000) + "s 内未嗅探到媒体地址）");
        handler.postDelayed(timeoutTask, timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs);
    }

    /** 判定并上报。可能在任意线程被调用。 */
    private void inspect(String rawUrl) {
        if (done || rawUrl == null) return;
        final String url = rawUrl;
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("data:") || u.startsWith("blob:")) return;

        boolean hit = u.contains(".m3u8") || u.contains(".mp4")
                || u.contains(".flv") || u.contains(".mkv")
                || u.contains("mime_type=video") || u.contains("format=m3u8");
        if (!hit) return;

        if (done) return;
        done = true;
        handler.removeCallbacks(timeoutTask);
        handler.post(() -> {
            try {
                if (callback != null) callback.onFound(url);
            } finally {
                destroy();
            }
        });
    }

    private void fail(final String reason) {
        if (done) return;
        done = true;
        handler.removeCallbacks(timeoutTask);
        handler.post(() -> {
            try {
                if (callback != null) callback.onFailed(reason);
            } finally {
                destroy();
            }
        });
    }

    /** 必须调用（或用完自动调用）：释放 WebView，避免内存泄漏与后台驻留 */
    public void destroy() {
        handler.removeCallbacks(timeoutTask);
        activity.runOnUiThread(() -> {
            try {
                if (webView != null) {
                    webView.stopLoading();
                    webView.setWebViewClient(new WebViewClient());
                    webView.loadUrl("about:blank");
                    webView.destroy();
                    webView = null;
                }
                if (holder != null) {
                    ViewGroup parent = (ViewGroup) holder.getParent();
                    if (parent != null) parent.removeView(holder);
                    holder.removeAllViews();
                    holder = null;
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public boolean isDone() {
        return done;
    }
}
