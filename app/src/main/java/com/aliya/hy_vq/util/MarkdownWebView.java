package com.aliya.hy_vq.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Markdown / HTML 预览用的 {@link WebView} 统一适配器。
 *
 * <p>主项目里有两处需要渲染 Markdown：<b>检查更新的版本详情弹窗</b>（GitHub Release
 * 的 release notes）与<b>文件管理的 md 预览</b>。过去两处各自 {@code new WebView()}
 * 并随手配几个 setting，导致：</p>
 *
 * <ul>
 *   <li>远程图片（README 里的 badge / 截图）<b>永远加载不出来</b>；</li>
 *   <li>链接点不动，用户看到可点的样子却点不动；</li>
 *   <li>外链在 app 内打开，而不是跳到系统浏览器；</li>
 *   <li>深色模式下 WebView 仍是白底闪一下。</li>
 * </ul>
 *
 * <p>这些行为在这里一次配好，两处共用，保证 <b>md 预览与更新日志的体验一致</b>，
 * 也让「md 标准」不只停留在字符串层面 —— 渲染端同样要对齐 GitHub 的观感。</p>
 *
 * <p>⚠️ 本类不做 <b>JavaScript</b>（md/HTML 预览不需要，且开着等于给任意
 * {@code .md} 文件一个脚本执行入口）。外链一律交给系统浏览器。</p>
 */
public final class MarkdownWebView {

    private MarkdownWebView() {
    }

    /**
     * 创建一个已配置好的预览 WebView。
     *
     * @param ctx        宿主上下文（需能拿到 Activity，用于外链）
     * @param bgColor    页面背景色（须与主题同步，否则深色模式下会白闪）
     * @param onFirstContent 首次内容渲染完成回调（用来按内容高度自适应，可为 null）
     */
    public static WebView create(Context ctx, int bgColor,
                                 final Runnable onFirstContent) {
        final WebView wv = new WebView(ctx);
        configure(wv, ctx, bgColor, onFirstContent);
        return wv;
    }

    /**
     * 就地配置一个 WebView（供已存在的实例复用）。
     *
     * <p><b>关键点</b>：</p>
     * <ol>
     *   <li>DOM storage / database 打开 → md 里的图片与字体不再因
     *       跨源策略被拦（部分图床会因此返回空图）；</li>
     *   <li>混合内容始终允许 → README 里的 <b>http 图片</b>能显示
     *       （GitHub 上大量 http 图片，这是「图片加载不出来」的主因）；</li>
     *   <li>关闭缩放但保留自适应宽度 → 与 GitHub 移动版一致；</li>
     *   <li>关 JavaScript；</li>
     *   <li>外链一律 {@code ACTION_VIEW} 交系统浏览器，<b>绝不在
     *       {@code WebView} 内加载</b>（避免把用户带离 app 或撑坏栈）。</li>
     * </ol>
     */
    public static void configure(final WebView wv, Context ctx, int bgColor,
                                 final Runnable onFirstContent) {
        try {
            wv.setBackgroundColor(bgColor);

            WebSettings s = wv.getSettings();
            // ① 本地内容优先（file:// 相对路径的图能用）
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            // ② 跨源：md 预览要读本地文件 + 远程图，两者都需要
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            // ③ 混合内容：README 里的 http 图片（GitHub 上很常见）
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setLoadsImagesAutomatically(true);
            // ④ 缩放：跟随系统字体，但禁止手动双指缩放（与 GitHub 移动版一致）
            s.setTextZoom(100);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setSupportZoom(false);
            // ⑤ 不用 JS（安全）
            try {
                s.setJavaScriptEnabled(false);
            } catch (Throwable ignore) {
                // 个别 ROM 抛异常，忽略
            }
            // ⑥ 排版：允许按容器宽度换行
            s.setUseWideViewPort(false);
            s.setLoadWithOverviewMode(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);

            // 外链 → 系统浏览器；站内 anchor（#章节）留在本页
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    return handleUrl(ctx, req != null ? req.getUrl() : null);
                }

                @SuppressWarnings("deprecation")
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleUrl(ctx, url != null ? Uri.parse(url) : null);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (onFirstContent != null) {
                        onFirstContent.run();
                    }
                }
            });
        } catch (Throwable ignore) {
            // 个别 ROM 的 WebView 初始化会抛异常；预览降级为「显示原始文本」也不至于崩
        }
    }

    /**
     * 处理预览里的链接点击。
     *
     * @return true = 已消费（不交给 WebView 加载）
     */
    private static boolean handleUrl(Context ctx, Uri uri) {
        if (uri == null) return false;
        String url = uri.toString();
        if (TextUtils.isEmpty(url)) return false;
        // 纯锚点：页内跳转，留在 WebView 里（GitHub README 的目录链接靠它）
        if (url.startsWith("#")) return false;
        try {
            String scheme = uri.getScheme();
            // 只放行 http/https/mailto，其余（javascript:、intent: 等）一律拒绝
            boolean safe = scheme == null
                    || "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "mailto".equalsIgnoreCase(scheme);
            if (!safe) return true;
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignore) {
            // 没有可处理的 app：静默失败，不弹「无法打开」
        }
        return true;
    }

    /**
     * 把 WebView 放进容器并按内容高度自适应（高度不足就滚动，高度富余就收缩）。
     *
     * <p>更新日志弹窗与 md 预览都需要这个 —— 固定高度会出现
     * 「内容被截断」或「大段空白下面拖着滚动条」。</p>
     *
     * @param container 父容器（高度 WRAP_CONTENT）
     * @param minDp     最小高度（dp）
     * @param maxDp     最大高度（dp），超出则内部滚动
     */
    public static void attachAutoHeight(final WebView wv, ViewGroup container,
                                        int minDp, int maxDp, Context ctx) {
        if (wv == null || container == null) return;
        final float density = ctx.getResources().getDisplayMetrics().density;
        final int minPx = (int) (minDp * density);
        final int maxPx = (int) (maxDp * density);

        ViewGroup.LayoutParams lp = container.getLayoutParams();
        if (lp != null) {
            container.setLayoutParams(lp);   // 保持调用方设的宽度
        }

        wv.setWebViewClient(new WebViewClient() {
            private boolean done;

            @Override
            public void onPageFinished(WebView view, String url) {
                if (done) return;
                done = true;
                try {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            int contentH = (int) (view.getContentHeight() * view.getScale());
                            int want = Math.max(minPx, Math.min(contentH + dp(12, density), maxPx));
                            ViewGroup.LayoutParams p = view.getLayoutParams();
                            if (p != null) {
                                p.height = want;
                                view.setLayoutParams(p);
                            }
                        }
                    });
                } catch (Throwable ignore) {
                    // ignore：高度不自适应也能看，只是可能多/少一截
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(ctx, Uri.parse(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(ctx, req != null ? req.getUrl() : null);
            }
        });
    }

    private static int dp(int v, float density) {
        return Math.round(v * density);
    }
}
