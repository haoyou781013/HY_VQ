package com.aliya.hy_vq.plugin;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * 插件入口 SPI（Service Provider Interface）。
 *
 * <p><b>这是插件侧唯一允许依赖的宿主类</b>。插件编译成独立 dex 后，
 * 通过它与宿主通信；宿主<b>不</b>反向依赖插件类（编译期单向依赖）。
 *
 * <p><b>加载时机</b>：宿主冷启动时 {@link PluginManager#loadAll()} 扫描
 * {@code filesDir/plugins/<id>/}，用 {@code DexClassLoader} 加载 {@code plugin.json}
 * 指定的入口类并调用 {@link #onAttach}。
 *
 * <p><b>可用范围</b>：插件能引用宿主 APK 里的公开类
 * （{@code ModuleUiKit}、{@code GhApi} 等），因为 ClassLoader 父加载器是 App 的。
 * 但引用越多，宿主重构时插件越容易失效 —— <b>建议插件只依赖本 SPI + Android SDK
 * + {@code ModuleUiKit} 这几个工具类</b>。
 *
 * <p><b>示例插件 manifest（plugin.json）</b>
 * <pre>
 * {
 *   "id": "github",
 *   "name": "GitHub",
 *   "version": "1.0.0",
 *   "entry": "com.aliya.hy_vq.plugins.github.GithubPlugin"
 * }
 * </pre>
 */
public interface HyVqPlugin {

    /**
     * 宿主上下文注入。
     *
     * @param app    Application 上下文（插件若需 Activity 上下文，自行用
     *               {@code ContextWrapper} 或让宿主在 {@link #createView} 时提供）
     * @param meta   插件自身 manifest 解析结果
     */
    void onAttach(Context app, PluginMeta meta);

    /** 插件名（供日志与列表显示），默认取 manifest 的 name */
    default String getName() {
        return "";
    }

    /** 插件版本，默认取 manifest 的 version */
    default String getVersion() {
        return "0.0.0";
    }

    /**
     * 创建插件主视图。
     *
     * @param inflater 必须用宿主传入的 inflater（其 Context 是 Activity，
     *                 取主题属性需要 Activity theme；见 GithubModule 踩过的坑）
     */
    View createView(LayoutInflater inflater, ViewGroup parent);

    /**
     * 创建插件设置视图（可选，返回 null 表示无）。
     * <p>插件功能精简后已下线，见 {@link PluginManager#uninstall}。
     */
    default View createSettingsView(LayoutInflater inflater, ViewGroup parent) {
        return null;
    }

    /** 插件在侧边栏 / 首页是否显示 */
    default boolean showInDrawer() {
        return true;
    }

    /** 侧边栏图标名（对应 ModuleUiKit.iconRes 支持的名字） */
    default String iconName() {
        return "code";
    }

    /** 一行摘要（列表里显示） */
    default String summary() {
        return "";
    }

    /** 宿主主动销毁插件时调用 */
    void onUnload();
}