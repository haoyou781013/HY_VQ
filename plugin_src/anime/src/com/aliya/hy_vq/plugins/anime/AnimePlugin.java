package com.aliya.hy_vq.plugins.anime;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.aliya.hy_vq.plugin.HyVqPlugin;
import com.aliya.hy_vq.plugin.PluginMeta;
import com.aliya.hy_vq.plugin.PluginUi;

/**
 * 动漫 插件（包装层）。
 *
 * <p>聚合多个动漫源搜索、播放与追番。
 *
 * <p>实现方式：不重写业务，调用 {@link PluginUi#createHostPage} 取宿主现成页面。
 * 原因见 PluginUi.HostPageFactory 的注释 —— 这些页面的实现用了大量
 * {@code R.drawable} / {@code R.id}，搬进 dex 会因资源 namespace 不匹配崩溃。
 */
public class AnimePlugin implements HyVqPlugin {

    /** 宿主页面标识。 */
    private static final String PAGE_KEY = "anime";

    private Context app;
    private PluginMeta meta;
    private View content;

    @Override
    public void onAttach(Context app, PluginMeta meta) {
        this.app = app;
        this.meta = meta;
    }

    @Override
    public View createView(LayoutInflater inflater, ViewGroup parent) {
        Context c = inflater.getContext();

        View host = PluginUi.createHostPage(PAGE_KEY, inflater, parent);
        if (host != null) {
            content = host;
            return host;
        }

        // 降级：宿主没提供这个页面时给一句人话，而不是白屏。
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(PluginUi.matchParent());
        int pad = PluginUi.dp(c, 20);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(PluginUi.background(c));
        box.addView(PluginUi.title(c, "动漫"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = PluginUi.dp(c, 12);
        box.addView(PluginUi.body(c,
                "宿主未提供该页面（key=" + PAGE_KEY + "）。\n\n"
                + "通常是宿主版本过旧，或该功能已在宿主侧下线。"), lp);
        content = box;
        return box;
    }

    @Override
    public void onUnload() {
        content = null;
    }

    @Override
    public String getName() {
        return "动漫";
    }

    @Override
    public String getVersion() {
        return meta != null ? meta.version : "1.0.0";
    }

    @Override
    public String summary() {
        return "多源聚合搜索与播放";
    }

    @Override
    public boolean showInDrawer() {
        return true;
    }

    @Override
    public String iconName() {
        return "anime";
    }
}
