package com.aliya.hy_vq;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.aliya.hy_vq.module.ModuleUiKit;
import com.aliya.hy_vq.player.PlayerCore;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 内置播放器（Media3 / ExoPlayer 统一内核）。
 *
 * <p>改造前用原生 {@code VideoView}，注释自承「本页只处理视频，音频走文件管理的弹窗播放器
 * ——{@code VideoView} 依赖 Surface，把 View 隐藏后音频无输出」。现在换成 {@link ExoPlayer}：
 * 它**不依赖 Surface**，音频可直接出声，于是音视频共用同一个内核。</p>
 *
 * <p>另一项关键收益：**能注入请求头**（{@link PlayerCore#create}）。实测采集到的番剧源
 * 几乎都要求特定 User-Agent / Referer / Cookie，而 {@code VideoView} 连 headers 重载都没有，
 * 且 {@code MediaPlayer} 的 headers 对 HLS 分片不生效。</p>
 *
 * <p>支持**同目录左右切换**：打开时收到当前目录下的音视频列表与起始索引，
 * 点「上一个 / 下一个」切换并自动播放（列表由文件管理收集同目录同类型文件）。</p>
 *
 * <p>新增：{@link #EXTRA_HEADERS} 可随 Intent 传入请求头（网络播放用），
 * 以及直接播放 http(s) 地址（在线源用）。</p>
 */
public class MediaPlayerActivity extends Activity {

    /** 应用级 DPI 覆盖：让「设置 → 显示密度」只对本应用生效（见 DpiUtils） */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.aliya.hy_vq.util.DpiUtils.wrap(base));
    }

    public static final String EXTRA_PATH = "media_path";     // 兼容单文件调用
    public static final String EXTRA_URI = "media_uri";       // 兼容单文件调用
    public static final String EXTRA_TITLE = "media_title";
    public static final String EXTRA_LIST = "media_list";     // String[] 播放列表
    public static final String EXTRA_INDEX = "media_index";   // 起始索引
    public static final String EXTRA_IS_URI = "media_is_uri"; // 列表项是否为 content://
    /** 请求头 Bundle（网络播放用）。键值均为 String。 */
    public static final String EXTRA_HEADERS = "media_headers";

    /**
     * 构造「播放单个网络地址」的 Intent —— 在线源（番剧/远程媒体）走这里。
     *
     * <p>本页原有的调用方 {@code FileManagerModule.openWithBuiltInPlayer} 是为**本地文件列表**
     * 设计的（会收集同目录文件做左右切换），网络地址没有"同目录"可言，所以单开这个入口。
     * 二者共用同一个 Media3 内核。</p>
     *
     * @param headers 请求头（Referer / User-Agent / Cookie 等），可为 null。
     *                注意：这些头会作用于**播放列表与全部分片请求**（见 PlayerCore 的说明）。
     */
    public static android.content.Intent urlIntent(android.content.Context ctx, String url,
                                                   String title, Map<String, String> headers) {
        android.content.Intent i = new android.content.Intent(ctx, MediaPlayerActivity.class);
        i.putExtra(EXTRA_URI, url);
        i.putExtra(EXTRA_IS_URI, true);
        i.putExtra(EXTRA_TITLE, title);
        if (headers != null && !headers.isEmpty()) {
            Bundle b = new Bundle();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    b.putString(e.getKey(), e.getValue());
                }
            }
            i.putExtra(EXTRA_HEADERS, b);
        }
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mpg", "mpeg", "rm", "rmvb", "vob", "f4v", "m2ts", "mts", "ogv", "mxf"));

    private PlayerView playerView;
    private ExoPlayer player;
    private View placeholder, controls, topBar;
    private ImageView btnPlay, btnPrev, btnNext;
    private SeekBar seekBar;
    private TextView tvPos, tvDur, tvTitle, tvCounter, tvAudioName;

    private String[] playlist;
    private int idx = 0;
    private boolean isUri = false;
    private Map<String, String> headers;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;
    private boolean controlsVisible = true;
    /** 是否已进入可播状态（对应原 VideoView 的 prepared 语义） */
    private boolean prepared = false;
    /** 标记"是我们主动切换"的加载，用于区分播放结束与出错 */
    private boolean switching = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            syncProgress();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_media_player);

        playerView = findViewById(R.id.video_view);
        placeholder = findViewById(R.id.audio_placeholder);
        controls = findViewById(R.id.player_controls);
        topBar = findViewById(R.id.player_top);
        btnPlay = findViewById(R.id.btn_player_play);
        btnPrev = findViewById(R.id.btn_player_prev);
        btnNext = findViewById(R.id.btn_player_next);
        ImageView btnBack = findViewById(R.id.btn_player_back);
        seekBar = findViewById(R.id.seek_player);
        tvPos = findViewById(R.id.tv_player_pos);
        tvDur = findViewById(R.id.tv_player_dur);
        tvTitle = findViewById(R.id.tv_player_title);
        tvCounter = findViewById(R.id.tv_player_counter);
        tvAudioName = findViewById(R.id.tv_audio_name);
        View root = findViewById(R.id.player_root);

        // ── 请求头（网络源需要）──
        Bundle hb = getIntent().getBundleExtra(EXTRA_HEADERS);
        if (hb != null && !hb.isEmpty()) {
            headers = new HashMap<>();
            for (String k : hb.keySet()) {
                String v = hb.getString(k);
                if (k != null && v != null) headers.put(k, v);
            }
        }

        // ── 组装播放列表（优先取列表，兼容旧的单文件传参）──
        String[] list = getIntent().getStringArrayExtra(EXTRA_LIST);
        int startIdx = getIntent().getIntExtra(EXTRA_INDEX, 0);
        isUri = getIntent().getBooleanExtra(EXTRA_IS_URI, false);
        if (list == null || list.length == 0) {
            String path = getIntent().getStringExtra(EXTRA_PATH);
            String uriStr = getIntent().getStringExtra(EXTRA_URI);
            String single = (path != null && !path.isEmpty()) ? path : uriStr;
            if (single == null || single.isEmpty()) {
                ModuleUiKit.toast(this, "缺少播放地址");
                finish();
                return;
            }
            isUri = (uriStr != null && !uriStr.isEmpty());
            list = new String[]{single};
            startIdx = 0;
        }
        playlist = list;
        idx = Math.max(0, Math.min(startIdx, playlist.length - 1));

        // ── 创建统一内核（headers 在这里生效，作用于播放列表与全部分片请求）──
        player = PlayerCore.create(this, headers);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    prepared = true;
                    switching = false;
                    int dur = (int) Math.max(0, player.getDuration());
                    seekBar.setMax(dur);
                    tvDur.setText(fmt(dur));
                    updatePlayIcon(player.isPlaying());
                    handler.removeCallbacks(ticker);
                    handler.post(ticker);
                    if (player.getPlayWhenReady() && !player.isPlaying()) {
                        // READY 但未播（如暂停后的 seek），保持用户意图不动
                    }
                } else if (state == Player.STATE_ENDED) {
                    updatePlayIcon(false);
                    // 循环列表：播完自动下一个，最后一个播完回到第一个
                    playAt(idx < playlist.length - 1 ? idx + 1 : 0);
                } else if (state == Player.STATE_BUFFERING) {
                    if (!prepared) tvDur.setText("缓冲中…");
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                switching = false;
                prepared = false;
                String msg = error.getErrorCodeName();
                tvDur.setText("无法播放");
                ModuleUiKit.toast(MediaPlayerActivity.this,
                        "播放失败：" + (msg == null ? "未知错误" : msg));
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayIcon(isPlaying);
            }
        });

        root.setOnClickListener(v -> toggleControls());
        btnBack.setOnClickListener(v -> finish());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnPrev.setOnClickListener(v -> step(-1));
        btnNext.setOnClickListener(v -> step(1));
        ImageView btnList = findViewById(R.id.btn_player_list);
        if (btnList != null) btnList.setOnClickListener(v -> showPlaylistPicker());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) tvPos.setText(fmt(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (prepared) player.seekTo(sb.getProgress());
            }
        });

        playAt(idx);
    }

    /** 切到第 i 个并自动播放 */
    private void playAt(int i) {
        if (playlist == null || i < 0 || i >= playlist.length || player == null) return;
        idx = i;
        final String path = playlist[i];
        String name = baseName(path);
        tvTitle.setText(name);
        tvCounter.setText((i + 1) + " / " + playlist.length);

        boolean isVideo = isVideoFile(name);
        placeholder.setVisibility(isVideo ? View.GONE : View.VISIBLE);
        playerView.setVisibility(isVideo ? View.VISIBLE : View.INVISIBLE);
        if (!isVideo) tvAudioName.setText(name);

        prepared = false;
        switching = true;
        updatePlayIcon(false);
        seekBar.setProgress(0);
        seekBar.setMax(0);
        tvPos.setText("00:00");
        tvDur.setText("00:00");
        btnPrev.setAlpha(i > 0 ? 1f : 0.35f);
        btnNext.setAlpha(i < playlist.length - 1 ? 1f : 0.35f);

        try {
            androidx.media3.common.MediaItem mi = PlayerCore.mediaItem(path, isUri);
            if (mi == null) {
                ModuleUiKit.toast(this, "无效的播放地址");
                return;
            }
            player.setMediaItem(mi);
            player.setPlayWhenReady(true);
            player.prepare();
        } catch (Throwable t) {
            ModuleUiKit.toast(this, "打开失败：" + t.getMessage());
        }
    }

    /** 左下角「选择文件」：列出当前播放列表（当前目录的音视频文件），点选即切换播放 */
    private void showPlaylistPicker() {
        if (playlist == null || playlist.length == 0) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "播放列表（" + playlist.length + "）"));

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sc.addView(col);
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().density * 300));
        box.addView(sc, slp);

        final android.app.Dialog d = ModuleUiKit.glassDialog(this, box);
        int primary = ModuleUiKit.color(this, com.google.android.material.R.attr.colorPrimary);
        int normal = ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface);
        int pad = (int) (getResources().getDisplayMetrics().density * 10);

        for (int i = 0; i < playlist.length; i++) {
            final int index = i;
            TextView tv = new TextView(this);
            tv.setText((i + 1) + ".  " + baseName(playlist[i]));
            tv.setTextSize(13);
            tv.setPadding(pad, pad, pad, pad);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            tv.setTextColor(i == idx ? primary : normal);
            if (i == idx) tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setOnClickListener(v -> {
                d.dismiss();
                playAt(index);
            });
            col.addView(tv);
        }
        d.show();
    }

    /** 左右切换（循环）：首尾相接，不做越界提示打断 */
    private void step(int delta) {
        if (playlist == null || playlist.length == 0) return;
        int n = idx + delta;
        if (n < 0) n = playlist.length - 1;
        if (n >= playlist.length) n = 0;
        playAt(n);
    }

    private static String baseName(String p) {
        if (p == null) return "";
        int s = p.lastIndexOf('/');
        return (s >= 0 && s < p.length() - 1) ? p.substring(s + 1) : p;
    }

    private boolean isVideoFile(String name) {
        if (name == null) return true;
        // 网络地址常带 ?query#fragment，必须先剥离——
        // 否则 "a.m3u8?token=x" 取到的后缀是 "m3u8?token=x"，落不进白名单，视频会被误判成音频。
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        int h = name.indexOf('#');
        if (h >= 0) name = name.substring(0, h);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return true;
        String ext = name.substring(dot + 1).toLowerCase();
        // 流媒体后缀也按视频处理
        if ("m3u8".equals(ext) || "mpd".equals(ext)) return true;
        return VIDEO_EXTS.contains(ext);
    }

    private void togglePlay() {
        if (player == null) return;
        if (!prepared) {
            playAt(idx);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            updatePlayIcon(false);
        } else {
            player.play();
            updatePlayIcon(true);
        }
    }

    private void updatePlayIcon(boolean playing) {
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void toggleControls() {
        controlsVisible = !controlsVisible;
        controls.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        topBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
    }

    private void syncProgress() {
        if (!prepared || dragging || player == null) return;
        try {
            int pos = (int) Math.max(0, player.getCurrentPosition());
            seekBar.setProgress(pos);
            tvPos.setText(fmt(pos));
            updatePlayIcon(player.isPlaying());
        } catch (Throwable ignored) {
        }
    }

    private String fmt(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    @Override protected void onPause() {
        super.onPause();
        try {
            if (player != null && player.isPlaying()) player.pause();
        } catch (Throwable ignored) {
        }
        updatePlayIcon(false);
        handler.removeCallbacks(ticker);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prepared) handler.post(ticker);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try {
            if (playerView != null) playerView.setPlayer(null);
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
