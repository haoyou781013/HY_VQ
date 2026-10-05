package com.aliya.hy_vq.anime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebView 页面渲染器 —— Kazumi 规则引擎的必要一环。
 *
 * <h3>为什么需要</h3>
 * 实测 KazumiRules 的 6 条抽样规则<b>全部标了 {@code useWebview: true}</b>，
 * 且纯 HTTP 抓取会拿到：
 * <ul>
 *   <li>Cloudflare 挑战页（如 acgpost.com 返回 "Attention Required! | Cloudflare"）</li>
 *   <li>JS 渲染前的空壳页（7sefun 仅 4.8KB，无搜索结果）</li>
 * </ul>
 * Kazumi 的做法就是<b>在 WebView 里加载页面再取 DOM</b>，本类复刻该能力：
 * 隐藏 WebView 加载 → 等页面完成 → 用 JS 取 {@code document.documentElement.outerHTML} → 回调。
 *
 * <h3>安全</h3>
 * 与 {@link WebViewLinkExtractor} 同理：必须开 JavaScript，因此<b>只在用户触发的
 * 解析流程里创建，取到 HTML 立即 destroy</b>，不留后台实例。
 */
public final class WebViewPageFetcher {

    public interface Callback {
        void onResult(String html);
    }

    private WebViewPageFetcher() {
    }

    /** 兜底用的 Context（由动漫页初始化时注入，避免每处传参） */
    private static volatile Context appCtx;

    public static void setAppContext(Context c) {
        if (c != null && appCtx == null) appCtx = c.getApplicationContext();
    }

    /**
     * 渲染页面并返回 HTML（阻塞当前线程）。适合在已有的后台线程里调用。
     *
     * @param url     目标页面
     * @param timeoutMs 超时（建议 12~15s）
     * @return 渲染后的 HTML；失败返回 null
     */
    public static String fetchBlocking(String url, long timeoutMs) {
        Context c = appCtx;
        if (c == null || url == null || url.isEmpty()) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final Handler main = new Handler(Looper.getMainLooper());

        main.post(() -> {
            WebView wv = null;
            try {
                Activity activity = (c instanceof Activity) ? (Activity) c : null;
                // 应用上下文无法直接 new WebView（需 Activity 主题），用全局上下文尝试
                wv = new WebView(c);
            } catch (Throwable t) {
                latch.countDown();
                return;
            }
            final WebView webView = wv;
            try {
                WebSettings st = webView.getSettings();
                st.setJavaScriptEnabled(true);
                st.setDomStorageEnabled(true);
                st.setLoadsImagesAutomatically(false);
                st.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

                final boolean[] done = {false};
                final Runnable finish = () -> {
                    if (done[0]) return;
                    done[0] = true;
                    webView.evaluateJavascript(
                            "(function(){return document.documentElement.outerHTML;})()",
                            value -> {
                                // JS 字符串字面量 → 解出真实文本
                                result.set(unescapeJs(value));
                                try { webView.destroy(); } catch (Throwable ignored) {}
                                latch.countDown();
                            });
                };

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String u) {
                        // 等一小会儿让 JS 完成挑战/渲染
                        new Handler(Looper.getMainLooper()).postDelayed(finish, 1200);
                    }

                    @Override
                    public void onReceivedError(WebView view, int code, String desc, String url) {
                        // 忽略子资源错误，主文档可能仍可用
                    }
                });

                webView.loadUrl(url);
                // 总超时兜底
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!done[0]) {
                        done[0] = true;
                        try { webView.destroy(); } catch (Throwable ignored) {}
                        latch.countDown();
                    }
                }, timeoutMs);
            } catch (Throwable t) {
                try { webView.destroy(); } catch (Throwable ignored) {}
                latch.countDown();
            }
        });

        try {
            latch.await(timeoutMs + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return result.get();
    }

    /** 把 JS 返回的字符串字面量还原为纯文本（含 unicode 转义、引号、换行） */
    private static String unescapeJs(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.equals("null")) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (Throwable t) { sb.append(n); }
                        } else sb.append(n);
                        break;
                    }
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(n);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
