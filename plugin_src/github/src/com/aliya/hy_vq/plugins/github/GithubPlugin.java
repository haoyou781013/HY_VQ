package com.aliya.hy_vq.plugins.github;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aliya.hy_vq.plugin.HyVqPlugin;
import com.aliya.hy_vq.plugin.PluginMeta;
import com.aliya.hy_vq.plugin.PluginUi;

/**
 * GitHub 插件。
 *
 * <p>从内置 {@code GithubModule} 剥离而来。剥离的动因是主程序
 * MainActivity 已 4687 行，侧边栏塞了 7 个一级入口，任何一处改动
 * 都得重新验证整个文件。
 *
 * <h3>与内置版的差异</h3>
 * 内置版继承 {@code HyVqModule}，能用 {@code ModuleUiKit} 和 {@code R.attr.*}。
 * 插件是独立 dex，<b>两样都不能碰</b>（见 HyVqPlugin 文档的禁止事项）。
 * 所以这里所有 UI 纯代码构建，颜色从宿主传入的 inflater 上下文里取主题属性。
 */
public class GithubPlugin implements HyVqPlugin {

    private Context app;
    private PluginMeta meta;
    private View root;

    @Override
    public void onAttach(Context app, PluginMeta meta) {
        this.app = app;
        this.meta = meta;
    }

    @Override
    public View createView(LayoutInflater inflater, ViewGroup parent) {
        // 用 inflater 的 Context 而非 app —— 它是 Activity，
        // 能解析主题属性；Application 上下文取不到主色，会整页透明。
        Context c = inflater.getContext();

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        int pad = PluginUi.dp(c, 20);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(PluginUi.background(c));

        box.addView(PluginUi.title(c, "GitHub"));

        TextView note = PluginUi.body(c, "插件版 v" + meta.version + "\n\n"
                + "剥离自内置模块，现已独立分发。\n"
                + "仓库浏览 / Issue / 代码查看功能正在迁移中。");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = PluginUi.dp(c, 16);
        box.addView(note, nlp);

        ScrollView scroll = new ScrollView(c);
        scroll.addView(box);
        return scroll;
    }

    @Override
    public void onUnload() {
        root = null;
    }

    @Override
    public String getName() {
        return "GitHub";
    }

    @Override
    public String getVersion() {
        return meta != null ? meta.version : "1.0.0";
    }

    @Override
    public String summary() {
        return "浏览仓库、Issue 与代码";
    }

    @Override
    public boolean showInDrawer() {
        return true;
    }

    @Override
    public String iconName() {
        return "github";
    }

}
