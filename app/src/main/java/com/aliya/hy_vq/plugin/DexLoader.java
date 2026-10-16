package com.aliya.hy_vq.plugin;

import android.content.Context;

import java.io.File;

import dalvik.system.DexClassLoader;

/**
 * 插件 ClassLoader：<b>子加载器</b>模式。
 *
 * <p><b>为什么不直接用 {@code new DexClassLoader(dexPath, optDir, null)}</b>
 * （不传父加载器）：那样会把 dex 放进宿主 ClassLoader 的搜索链里，
 * 插件中的 {@code com.aliya.hy_vq.*} 类会被<b>重复加载</b> —— 宿主一份、插件一份。
 * 于是插件里拿到的 {@code ModuleUiKit} 不是宿主那个，
 * {@code instanceof} / 静态状态 / 主题解析全乱。
 *
 * <p>这里显式传入宿主 ClassLoader 作为父加载器：
 * 插件 dex 只在本层可见，找不到的类委派给父加载器 → <b>宿主类全局只有一份</b>。
 *
 * <p><b>optimizedDirectory 必须是 App 私有目录</b>（不能是 sdcard），
 * 否则 oat/dopt 缓存不可写，会在加载时报
 * {@code Unable to create dexopt file}。
 */
final class DexLoader extends DexClassLoader {

    DexLoader(Context context, String dexPath, String optDir) {
        super(dexPath, new File(optDir).getAbsolutePath(), null,
                context.getClassLoader());
    }
}