package com.aliya.hy_vq;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局异常兜底：把未捕获异常写入文件，便于事后定位闪退。
 *
 * <h3>为什么需要它</h3>
 * 2026-10-18 的 2.38.1 出现「闪一下就退」，但设备连不上 adb、拿不到 logcat，
 * 完全无法判断崩在哪。这种情况只能靠应用自己把堆栈落盘。
 *
 * <p>崩溃日志写到 {@code filesDir/crash/last-crash.txt}，用文件管理器就能取。
 * 用 {@link BuildConfig#DEBUG} 之外的场景也照常写入 —— 用户装的就是 release 包，
 * 只在 debug 写日志等于没写。
 *
 * <p>不打算在这里"吞掉"崩溃：记录完仍然交给系统默认处理器，
 * 避免应用处于半死不活的状态。
 */
public class HyVqApp extends Application {

    private static final String TAG = "HyVqApp";
    private static final String DIR = "crash";
    private static final String FILE = "last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashHandler(this);
    }

    /** 安装未捕获异常处理器。重复调用安全。 */
    public static void installCrashHandler(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                writeCrash(ctx, t, e);
            } catch (Throwable ignored) {
                // 记录本身失败时绝不能掩盖原始崩溃
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void writeCrash(Context ctx, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(new Date()));
        pw.println("thread: " + (t == null ? "?" : t.getName()));
        pw.println("version: " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ")");
        pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println();
        e.printStackTrace(pw);
        pw.flush();

        File dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建崩溃日志目录");
            return;
        }
        File f = new File(dir, FILE);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
            fos.write(sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ioe) {
            // 写盘失败不影响后续流程，堆栈仍在 Logcat 里
            Log.w(TAG, "写崩溃日志失败", ioe);
        }
        Log.e(TAG, "崩溃已记录到 " + f.getAbsolutePath() + "\n" + sw);
    }
}
