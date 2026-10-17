package com.aliya.hy_vq.plugin;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * 插件入口 SPI（Service Provider Interface）。
 *
 * <p><b>这是插件侧唯一允许依赖的宿主契约。</b>插件编译成独立 dex 后通过它
 * 与宿主通信；宿主<b>不</b>反向依赖插件类（编译期单向依赖）。
 *
 * <h3>加载时机</h3>
 * 宿主冷启动时 {@code PluginManager.loadAll()} 扫描
 * {@code filesDir/plugins/<id>/}，用 {@code DexClassLoader} 加载
 * {@code plugin.json} 里 {@code entry} 指定的类并调用 {@link #onAttach}。
 * <b>不是安装即生效</b> —— dex 只能在进程启动时加载。
 *
 * <h3>侧边栏动态加载</h3>
 * 宿主侧边栏<b>不写死</b>插件入口，而是遍历已加载插件，
 * 对 {@link #showInDrawer()} 为 true 的逐个建条目。
 * 卸载插件后重启，入口自动消失。
 * 侧边栏顺序按 {@link #order()} 升序，同序则按 id。
 *
 * <h3>可用范围（重要）</h3>
 * 插件的 ClassLoader 父加载器是宿主 App 的，所以<b>能</b>引用宿主 APK 里的
 * 公开类。但这带来耦合：宿主重构后插件会 NoSuchMethodError。
 *
 * <p><b>依赖策略（2026-10-17 起放宽）</b>
 *
 * <p>原策略是「插件只许依赖本 SPI + Android SDK」，宿主类一律禁用。
 * 实测这个限制过严：动漫 7186 行、抽卡 2288 行全都深度依赖
 * {@code ModuleUiKit} 和 {@code R.attr.*}，按原策略改写等于重写业务层，
 * 收益远小于风险。
 *
 * <p>现在<b>允许插件直接依赖宿主公开类</b>，包括 {@code ModuleUiKit}、
 * {@code GhApi}、{@code access.*}（文件管理）等。代价是宿主重构后插件会
 * {@code NoSuchMethodError}，需要同步重发插件。
 *
 * <p>仍然保留的红线见下方「插件禁止事项」。要主题色请用
 * {@link PluginUi} —— 它按语义封装了 {@code R.attr} 查询，
 * 插件不必也不能自己碰 R。
 * <h3>插件禁止事项</h3>
 * <ul>
 *   <li><b>不能用 R.*</b>：插件的资源 id 与宿主不在同一 namespace，
 *       引用会拿到错误资源或直接崩。所有 UI 必须纯代码构建。</li>
 *   <li><b>不能声明 Activity/Service</b>：插件是 dex，不参与宿主 manifest 合并。</li>
 *   <li><b>不能弹独立窗口</b>：只能通过 {@link #createView} 提供内容视图。</li>
 * </ul>
 *
 * <h3>plugin.json 示例</h3>
 * <pre>
 * {
 *   "id": "github",
 *   "name": "GitHub",
 *   "version": "1.0.0",
 *   "entry": "com.aliya.hy_vq.plugins.github.GithubPlugin",
 *   "summary": "浏览仓库、Issue 与代码",
 *   "icon": "code",
 *   "order": 100,
 *   "minHostVersion": 109
 * }
 * </pre>
 */
public interface HyVqPlugin {

    /**
     * 宿主上下文注入。加载后、{@link #createView} 之前调用一次。
     *
     * @param app   Application 上下文
     * @param meta  插件 manifest 解析结果
     */
    void onAttach(Context app, PluginMeta meta);

    /**
     * 创建插件主视图。
     *
     * @param inflater 宿主传入的 inflater。其 Context 是 <b>Activity</b>，
     *                 取主题属性（{@code R.attr.colorPrimary} 等）需要它；
     *                 用 Application 上下文会取不到色（宿主 GithubModule
     *                 曾因此整页透明，见 06-journal 第 ⑨ 节）。
     * @param parent   父容器，可为 null
     * @return 插件根视图。**必须**自己 setLayoutParams(MATCH_PARENT, MATCH_PARENT)，
     *         否则在 weight-based 容器里会测量成 0 高度。
     */
    View createView(LayoutInflater inflater, ViewGroup parent);

    /**
     * 卸载回调。宿主在 {@code PluginManager.uninstall} 时调用，
     * 插件应在此停止线程、注销监听、释放资源。
     */
    void onUnload();

    /** 插件名（侧边栏与商店显示）。默认取 manifest 的 name。 */
    default String getName() {
        return "";
    }

    /** 插件版本。默认取 manifest 的 version。 */
    default String getVersion() {
        return "0.0.0";
    }

    /** 一行摘要（商店列表显示）。默认取 manifest 的 summary。 */
    default String summary() {
        return "";
    }

    /**
     * 是否在侧边栏显示入口。
     * <p>返回 false 的插件仍可在设置页插件列表里看到并卸载。</p>
     */
    default boolean showInDrawer() {
        return true;
    }

    /**
     * 侧边栏图标名。支持的内置名见宿主 {@code PluginIcons.get(name)}：
     * <pre>code / github / anime / emoji / share / game / star / folder / info</pre>
     * 未知名字回退到 {@code code}。
     */
    default String iconName() {
        return "code";
    }

    /**
     * 侧边栏排序权重，小的在前。
     * <p>约定区间：</p>
     * <ul>
     *   <li>100–199 内容类（GitHub、动漫）</li>
     *   <li>200–299 工具类（软件分享、表情包）</li>
     *   <li>300–399 游戏辅助（原神帮助）</li>
     * </ul>
     */
    default int order() {
        return 500;
    }

    /**
     * 插件设置视图（可选）。返回 null 表示无设置页。
     * <p>店主在设置页插件条目里点「设置」时进入。</p>
     */
    default View createSettingsView(LayoutInflater inflater, ViewGroup parent) {
        return null;
    }

    /**
     * 插件被禁用时是否仍显示侧边栏入口（灰显）。
     * <p>默认 false —— 禁用后入口直接消失，符合「功能随用户添加」。</p>
     */
    default boolean visibleWhenDisabled() {
        return false;
    }
}
