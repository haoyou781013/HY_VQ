package com.aliya.hy_vq;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;

import com.aliya.hy_vq.databinding.ActivityMainBinding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.drawerlayout.widget.DrawerLayout;
import android.os.Build;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.aliya.hy_vq.module.HyVqModule;
import com.aliya.hy_vq.module.ModuleRegistry;
import com.aliya.hy_vq.module.ModuleServices;
import com.aliya.hy_vq.module.impl.FileManagerModule;
import com.aliya.hy_vq.module.ModuleUiKit;
import com.aliya.hy_vq.module.PermissionCenterView;
import com.aliya.hy_vq.update.HyVqAppEntry;
import androidx.core.content.FileProvider;
import com.aliya.hy_vq.update.UpdateManager;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import dalvik.system.DexClassLoader;
import org.json.JSONObject;
import android.content.res.Resources;
import com.aliya.hy_vq.module.HyVqTheme;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PICK_AVATAR = 1001;
    private static final int REQ_NOTIFICATION = 1003;
    private static final int REQ_SAF_MOUNT = 1005;

    // ── 页面索引常量 ──
    // 固定页使用静态索引；模块页使用 PAGE_MODULE_BASE 起的动态索引，
    // 避免多个页面共用同一页码导致返回逻辑/动画方向判断混乱。
    private static final int PAGE_HOME = 0;
    private static final int PAGE_FILEMGR = 1;
    private static final int PAGE_ABOUT = 2;
    private static final int PAGE_UPDATE = 8;
    /** 远程检查状态：0 未检查 / 1 检查中 / 2 检查完成无新版 / 4 检查失败 */
    private int updState = 0;
    private String updError = "";
    /** 扫描到的全部可用版本（按版本号倒序，第 0 个为最新） */
    private java.util.List<ReleaseInfo> updReleases = new java.util.ArrayList<>();
    /** 本机缓存中可安装的更新包（比当前版本新） */
    private File updCachedApk;
    /** 已弹过更新提示的版本号：同一版本不在本次会话内重复打扰 */
    /** 已自动提示过的版本（⭐ static：同一进程内同版本只提示一次，Activity 重建不重置） */
    private static String autoPromptedVer = "";
    /** ⭐ 每次「进入软件」（进程创建）只自动检测一次更新 —— static 守卫，Activity 重建不重复 */
    private static boolean launchUpdateChecked = false;
    /** ⭐ 更新流程进行中（下载/校验/安装）：期间一律不再自动弹「发现新版本」 */
    private static volatile boolean updateFlowBusy = false;
    /** 历史版本列表是否展开（默认收起，避免列表过长） */
    private boolean historyExpanded = false;
    /** 国内源只读凭据内存缓存（避免每次请求都走 Keystore 解密） */
    private String cachedCnCred;
    /** 本轮请求是否已因 401 刷新过凭据（防止无限重试） */
    private boolean credRetried = false;
    /** 开源仓库地址（与 README / LICENSE 一致）
     *  2026-09-27：仓库迁至新账号 —— 旧账号因双重验证密钥丢失无法登录，详见 MIGRATION.md */
    private static final String OPEN_SOURCE_URL = "https://github.com/haoyou781013/HY_VQ";
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_ACCOUNT = 4;
    private static final int PAGE_PERMISSIONS = 7;
    /** 实用软件分享（v2.9.1 内嵌页） */
    private static final int PAGE_SHARE = 9;
    /** 鸣潮表情包（v2.9.1 内嵌页） */
    private static final int PAGE_EMOJI = 10;
    /** 抽卡分析（原神祈愿记录） */
    private static final int PAGE_GACHA = 11;
    /** 原神帮助页（含抽卡分析入口） */
    private static final int PAGE_GENSHIN_HELP = 13;
    private static final int PAGE_ANIME = 12;      // 动漫（多源聚合搜索）
    private static final int PAGE_MODULE_BASE = 100;

    private ActivityMainBinding binding;
    private ActionBarDrawerToggle toggle;

    private View homeView, settingsView, accountView, permissionsView, aboutView, updateView;
    private ViewGroup contentFrame;
    private SharedPreferences prefs;
    private SignatureManager signatureManager;

    private int currentPageIndex = PAGE_HOME;
    private int nextModulePageIndex = PAGE_MODULE_BASE;
    /** 当前正在展示的模块 id（非模块页时为 null），用于统一返回逻辑 */
    private String currentModuleId = null;
    /** 文件管理：内置一级功能，2026-09-06 起从模块体系剥离为非模块 */
    private FileManagerModule fileManager;
    /** 抽卡分析页（懒建，仅首次进入时创建） */
    private com.aliya.hy_vq.gacha.GachaView gachaView;
    /** 原神帮助页（懒建） */
    private View genshinHelpView;

    // ── 模块系统 ──
    private ModuleRegistry moduleRegistry = new ModuleRegistry();
    private ModuleServices moduleServices;
    private LinearLayout drawerModuleSlot;

    private ImageView drawerAvatar, editAvatar;
    private View themeOverlay;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private TextView drawerUsername;
    private String avatarPath;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideHintRunnable;

    private final List<Dialog> activeDialogs = new ArrayList<>();
    /** 正在等待 onActivityResult 的弹窗（onStop 时不销毁，返回后继续使用） */
    private Dialog dialogAwaitingResult = null;

    /** 应用级 DPI 覆盖：让「设置 → 显示密度」只对本应用生效（见 DpiUtils） */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.aliya.hy_vq.util.DpiUtils.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 应用主题色叠加（仿 ZhuFiler：7 套 ThemeOverlay + Material You 动态色）
        ThemeHelper.applyThemeColor(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 2026-09-06: 主题模式固定为浅色（个性化功能已整体移除，待后续版本恢复）
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        signatureManager = new SignatureManager(this);
        avatarPath = prefs.getString("avatar_path", "");

        // 首次启动检查
        if (!prefs.getBoolean("has_logged_in", false)) {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // 规范：新功能先检查所需运行时权限，缺少则向系统申请
        requestRuntimePermissions();

        contentFrame = binding.contentFrame;
        drawerModuleSlot = binding.navView.findViewById(R.id.drawer_module_slot);

        setupToolbar();
        setupDrawer();
        setupDrawerNavigation();
        setupBackNavigation();
        initModuleSystem();
        // 增量更新引擎：启动兜底清理 + 加载更新包入口（幂等）
        UpdateManager.boot(this);

        // 初始加载
        handler.postDelayed(this::switchToHome, 50);
        updateDrawerUsername();
    }

    // ==================== 运行时权限（规范：新功能先检查权限，缺则向系统申请） ====================

    /** 通知权限：targetSdk 33+ 不申请，前台服务通知会被系统静默隐藏 */
    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 规范：申请成功后不弹窗；仅同步权限页状态展示
        if (requestCode == REQ_NOTIFICATION) {
            if (permissionsView != null && permissionsView.getParent() != null) {
                refreshPermissionsView();
            }
        }
    }

    // ==================== 模块系统 ====================
    private void initModuleSystem() {
        moduleServices = new ModuleServices() {
            @Override public String getUid() { return signatureManager.getUid(); }
            @Override public String getUsername() { return signatureManager.getUsername(); }
            @Override public String getUserSignature() { return prefs.getString("signature", "未设置签名"); }
            @Override public SharedPreferences getModulePrefs(String moduleId) {
                return getSharedPreferences("module_" + moduleId, MODE_PRIVATE);
            }
            @Override public void navigateTo(String page) {
                switch (page) {
                    case "home": switchToHome(); break;
                    case "settings": switchToSettings(); break;
                    case "account": switchToAccount(); break;
                    default: Toast.makeText(MainActivity.this, "未知页面: " + page, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void requestDrawerRebuild() {
                handler.post(() -> rebuildDrawerModuleSlot());
            }
        };
        moduleRegistry.setDrawerCallback(modules -> handler.post(() -> rebuildDrawerModuleSlot()));
        // 2026-09-06: 聊天模块暂时下线（只保留「文件管理」插件，待后续版本恢复）
        // 文件管理：内置一级功能（非模块）。从模块体系剥离后不再注册到 ModuleRegistry，
        // 因此不出现在模块列表、不受模块启停控制，由侧边栏/首页入口直接打开。
        fileManager = new FileManagerModule();
        fileManager.onAttach(this, moduleServices);
        // 加载已持久化的外置模块
        loadInstalledModules();
        // 首次构建侧边栏模块区
        handler.postDelayed(this::rebuildDrawerModuleSlot, 100);
        // 启动时自动检查更新（可在「设置 → 软件更新」关闭）
        autoCheckUpdateOnLaunch();
        // 阶段 4：使用条款与免责声明（首次启动强制同意）
        enforceAgreement();
    }

    // ══════════════ 阶段 4：告知与合规 ══════════════

    /** 用户是否已同意使用条款（改版时递增版本号即可重新征询） */
    private static final String KEY_AGREEMENT = "agreement_accepted_v1";

    private void enforceAgreement() {
        if (prefs.getBoolean(KEY_AGREEMENT, false)) return;
        showAgreementDialog();
    }

    /**
     * 首启协议弹窗：不可取消、点外部不关，必须明确选择。
     *
     * <p>之所以把「动漫模块」单列一节而不是塞进免责声明：它引入了本软件
     * 最特殊的两项外部依赖 —— 第三方内容源与内置浏览器（开启 JavaScript）。
     * 用户有权在首次使用前就知道。</p>
     */
    private void showAgreementDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "使用条款与免责声明"));

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        body.setLineSpacing(0, 1.25f);
        body.setText(String.join(System.lineSeparator(), new String[]{
            "一、软件性质",
            "本软件全部代码由 AI 智能体编写，按「现状」提供，不提供任何形式担保。",
            "请自行审阅代码并备份重要数据。",
            "",
            "二、权限与风险",
            "涉及文件操作（含 Root 权限）与外部模块动态加载，存在误操作风险。",
            "因使用造成的损失由使用者自行承担。",
            "",
            "三、动漫模块（重要）",
            "1. 该模块用于聚合第三方网站内容，数据源来自你自行导入的订阅。",
            "2. 本软件不提供、不存储、不分发任何内容，仅做解析与播放。",
            "3. 为解析部分加密源，模块会启动内置浏览器并开启 JavaScript，",
            "   这会扩大安全面。若不接受，请勿使用该模块。",
            "4. 第三方源随时可能失效（实测约半数规则已失效），属此类工具的",
            "   固有问题，不代表本软件故障。",
            "5. 请确保你的使用行为符合当地法律法规。",
            "",
            "四、开源",
            "以 GNU GPL v3.0 协议开源，可自由使用、修改与再分发。"
        }));
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        box.addView(sv, new LinearLayout.LayoutParams(-1, dpMain(360)));

        final Dialog dialog = ModuleUiKit.glassDialog(this, box, false);
        dialog.setCancelable(false);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dpMain(12);
        actions.setLayoutParams(alp);

        TextView decline = aboutBtn("不同意，退出", ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant), ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        decline.setOnClickListener(v -> {
            ModuleUiKit.dismissWithAnim(dialog);
            finishAffinity();
        });
        actions.addView(decline, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView accept = aboutBtn("同意并继续", ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnPrimary), ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(0, -2, 1f);
        acp.leftMargin = dpMain(8);
        accept.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_AGREEMENT, true).apply();
            ModuleUiKit.dismissWithAnim(dialog);
        });
        actions.addView(accept, acp);
        box.addView(actions);

        dialog.show();
    }

    /** 项目统一的按钮写法（圆角 TextView，非原生 Button） */
    private TextView aboutBtn(String text, int fg, int bg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(fg);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpMain(12), dpMain(10), dpMain(12), dpMain(10));
        t.setBackground(ModuleUiKit.rippleBg(this, ModuleUiKit.rounded(this, 10, bg, 0)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private int dpMain(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void rebuildDrawerModuleSlot() {
        if (drawerModuleSlot == null) return;
        // 2026-09-06: 侧边栏模块入口整体隐藏（只保留文件管理，由首页卡片直达）
        drawerModuleSlot.removeAllViews();
        drawerModuleSlot.setVisibility(View.GONE);
        if (true) return;
        List<HyVqModule> drawerMods = moduleRegistry.getDrawerModules();
        if (drawerMods.isEmpty()) {
            drawerModuleSlot.setVisibility(View.GONE);
        } else {
            drawerModuleSlot.setVisibility(View.VISIBLE);
            for (HyVqModule m : drawerMods) {
                View item = LayoutInflater.from(this).inflate(R.layout.item_drawer_module, drawerModuleSlot, false);
                item.setTag(m); // 供 updateDrawerSelection 匹配选中态
                ImageView icon = item.findViewById(R.id.drawer_item_icon);
                TextView text = item.findViewById(R.id.drawer_item_text);
                View dot = item.findViewById(R.id.drawer_item_dot);
                text.setText(m.name);
                // 模块图标按名称解析（模块 UI 适配：外置模块无需自带图标资源）
                icon.setImageResource(ModuleUiKit.iconRes(this, m.icon, R.drawable.ic_chat));
                dot.setVisibility(m.enabled ? View.VISIBLE : View.GONE);
                item.setOnClickListener(v -> {
                    binding.drawerLayout.closeDrawers();
                    binding.drawerLayout.postDelayed(() -> switchToModule(m), 160);
                });
                drawerModuleSlot.addView(item);
            }
        }
        updateDrawerSelection();
    }

    /**
     * 根据当前页面更新侧边栏选中态：
     * 首页高亮仅当 currentPageIndex == PAGE_HOME；模块项高亮仅当 currentModuleId 匹配。
     * 所有页面切换后都会调用，保证边栏状态与实际页面同步。
     */
    private void updateDrawerSelection() {
        if (binding == null) return;
        boolean homeSelected = currentPageIndex == PAGE_HOME && currentModuleId == null;
        View navHome = binding.navView.findViewById(R.id.nav_home);
        if (navHome != null) {
            navHome.setBackgroundResource(homeSelected ? R.drawable.bg_nav_item_selected : android.R.color.transparent);
            ImageView icon = navHome.findViewById(R.id.nav_home_icon);
            TextView text = navHome.findViewById(R.id.nav_home_text);
            int tint = resolveAttr(homeSelected
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (icon != null) icon.setColorFilter(tint);
            if (text != null) text.setTextColor(tint);
        }
        // 文件管理一级入口选中态
        View navFm = binding.navView.findViewById(R.id.nav_filemgr);
        if (navFm != null) {
            boolean fmSelected = currentPageIndex == PAGE_FILEMGR;
            navFm.setBackgroundResource(fmSelected
                    ? R.drawable.bg_nav_item_selected : R.drawable.bg_nav_item_default);
            ImageView fmIcon = navFm.findViewById(R.id.nav_filemgr_icon);
            TextView fmText = navFm.findViewById(R.id.nav_filemgr_text);
            int fmTint = resolveAttr(fmSelected
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (fmIcon != null) fmIcon.setColorFilter(fmTint);
            if (fmText != null) fmText.setTextColor(fmTint);
        }
        // 原神帮助一级入口选中态
        View navGacha = binding.navView.findViewById(R.id.nav_gacha);
        if (navGacha != null) {
            boolean gSelected = currentPageIndex == PAGE_GENSHIN_HELP
                    || currentPageIndex == PAGE_GACHA;
            navGacha.setBackgroundResource(gSelected
                    ? R.drawable.bg_nav_item_selected : R.drawable.bg_nav_item_default);
            ImageView gIcon = navGacha.findViewById(R.id.nav_gacha_icon);
            TextView gText = navGacha.findViewById(R.id.nav_gacha_text);
            int gTint = resolveAttr(gSelected
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (gIcon != null) gIcon.setColorFilter(gTint);
            if (gText != null) gText.setTextColor(gTint);
        }
        // 动漫一级入口选中态
        View navAnime = binding.navView.findViewById(R.id.nav_anime);
        if (navAnime != null) {
            boolean aSel = currentPageIndex == PAGE_ANIME;
            navAnime.setBackgroundResource(aSel
                    ? R.drawable.bg_nav_item_selected : R.drawable.bg_nav_item_default);
            ImageView aIcon = navAnime.findViewById(R.id.nav_anime_icon);
            TextView aText = navAnime.findViewById(R.id.nav_anime_text);
            int aTint = resolveAttr(aSel
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (aIcon != null) aIcon.setColorFilter(aTint);
            if (aText != null) aText.setTextColor(aTint);
        }
        // 鸣潮表情包选中态
        View navEmoji = binding.navView.findViewById(R.id.nav_emoji);
        if (navEmoji != null) {
            boolean eSel = currentPageIndex == PAGE_EMOJI;
            navEmoji.setBackgroundResource(eSel
                    ? R.drawable.bg_nav_item_selected : R.drawable.bg_nav_item_default);
            ImageView eIcon = navEmoji.findViewById(R.id.nav_emoji_icon);
            TextView eText = navEmoji.findViewById(R.id.nav_emoji_text);
            int eTint = resolveAttr(eSel
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (eIcon != null) eIcon.setColorFilter(eTint);
            if (eText != null) eText.setTextColor(eTint);
        }
        // 实用软件分享选中态
        View navShare = binding.navView.findViewById(R.id.nav_share);
        if (navShare != null) {
            boolean sSel = currentPageIndex == PAGE_SHARE;
            navShare.setBackgroundResource(sSel
                    ? R.drawable.bg_nav_item_selected : R.drawable.bg_nav_item_default);
            ImageView sIcon = navShare.findViewById(R.id.nav_share_icon);
            TextView sText = navShare.findViewById(R.id.nav_share_text);
            int sTint = resolveAttr(sSel
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            if (sIcon != null) sIcon.setColorFilter(sTint);
            if (sText != null) sText.setTextColor(sTint);
        }
        if (drawerModuleSlot != null) {
            for (int i = 0; i < drawerModuleSlot.getChildCount(); i++) {
                View item = drawerModuleSlot.getChildAt(i);
                Object tag = item.getTag();
                boolean selected = tag instanceof HyVqModule
                        && currentModuleId != null
                        && currentModuleId.equals(((HyVqModule) tag).id);
                item.setBackgroundResource(selected ? R.drawable.bg_nav_item_selected : android.R.color.transparent);
            }
        }
    }

    private void switchToModule(HyVqModule m) {
        if (m == null) return;
        View moduleView = m.createMainView(LayoutInflater.from(this), contentFrame);
        if (moduleView == null) return;
        currentModuleId = m.id;
        // 每个模块分配唯一动态页码，与个性化页(5)不再冲突
        switchContent(moduleView, nextModulePageIndex++);
        resetToolbar();
        binding.toolbarTitle.setText(m.name);
    }

    // ==================== 关于页 ====================

    private void switchToAbout() {
        if (aboutView == null) {
            aboutView = LayoutInflater.from(this).inflate(R.layout.fragment_about, contentFrame, false);
            setupAboutView();
        }
        switchContent(aboutView, PAGE_ABOUT);
        setSubpageToolbar("关于");
    }

    /**
     * 支持开发弹窗（v2.9.4）。
     *
     * <p>用户要求：关于页**不直接展示收款码**，只放一个按钮，点开才弹窗显示；
     * 且更新日志不写、软件内不主动提醒（主打免费简洁）。</p>
     */
    private void showDonateDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "支持开发"));

        TextView tip = new TextView(this);
        tip.setText("本软件完全免费、无广告。\n如果它帮到了你，可自愿扫码支持——不影响任何功能。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tip.setLineSpacing(0, 1.35f);
        tip.setTextColor(resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tip.setPadding(dp2(4), dp2(2), dp2(4), 0);
        box.addView(tip);

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.wechat_donate);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp2(230), dp2(230));
        qp.gravity = Gravity.CENTER_HORIZONTAL;
        qp.topMargin = dp2(14);
        box.addView(qr, qp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp2(14);
        box.addView(btns, blp);

        final Dialog dialog = ModuleUiKit.glassDialog(this, box, true);
        btns.addView(dialogTextButton("关闭", v -> dialog.dismiss()));
        dialog.show();
    }

    private void setupAboutView() {
        // 「支持开发」按钮入口（v2.9.4：不再在关于页直接铺收款码）
        View btnDev = aboutView.findViewById(R.id.btn_support_dev);
        if (btnDev != null) btnDev.setOnClickListener(v -> showDonateDialog());
        if (aboutView == null) return;
        int code = 0;
        try {
            code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        TextView tvVer = aboutView.findViewById(R.id.tv_about_version);
        if (tvVer != null) tvVer.setText("v" + baseVersionName() + " (code " + code + ")");
        TextView tvRepo = aboutView.findViewById(R.id.tv_about_repo);
        if (tvRepo != null) tvRepo.setText(OPEN_SOURCE_URL.replace("https://", ""));

        // ── 致谢：主列表常显，完整列表默认折叠 ──
        // 致谢数据见类字段 CREDITS_USED / CREDITS_INSPIRED / CREDITS_HISTORY

        // 致谢：点击打开独立弹窗（分「引用 / 借鉴 / 历史」三板块）
        View itemCredits = aboutView.findViewById(R.id.item_about_credits);
        if (itemCredits != null) itemCredits.setOnClickListener(v -> showCreditsDialog());

        TextView tvDisc = aboutView.findViewById(R.id.tv_about_disclaimer);
        if (tvDisc != null) {
            tvDisc.setText(String.join(System.lineSeparator(), new String[]{
                    "本软件全部代码由 AI 智能体编写，人类作者仅提出需求与验收结果。",
                    "",
                    "AI 生成的代码不保证正确性、安全性、稳定性与适用性，可能存在未发现的缺陷、性能问题或安全隐患。",
                    "",
                    "涉及文件操作（含 Root 权限）与外部模块动态加载，使用前请自行审阅代码并备份重要数据。",
                    "",
                    "本软件按「现状」提供，不提供任何形式担保；因使用造成的任何损失由使用者自行承担。",
                    "",
                    "【动漫模块】",
                    "该模块聚合第三方网站内容，数据源来自用户自行导入的订阅；本软件不提供、不存储、不分发任何内容，仅做解析与播放。",
                    "源由第三方维护，随时可能失效（实测约半数规则已失效），属此类工具的固有问题。",
                    "为解析部分加密源，模块会启动内置浏览器并开启 JavaScript，这会扩大安全面。",
                    "请确保使用行为符合当地法律法规。"
            }));
        }
        TextView tvCr = aboutView.findViewById(R.id.tv_about_copyright);
        if (tvCr != null) {
            tvCr.setText(String.join(System.lineSeparator(), new String[]{
                    "© 2026 HY_VQ · AI 编写",
                    "以 GNU GPL v3.0 协议开源，不提供任何担保",
                    "可自由使用、修改与再分发，衍生作品须采用同一协议"
            }));
        }
        aboutView.findViewById(R.id.item_about_repo).setOnClickListener(v -> openUrl(OPEN_SOURCE_URL));
        aboutView.findViewById(R.id.item_about_license).setOnClickListener(v -> showLicenseDialog());
        aboutView.findViewById(R.id.item_about_changelog).setOnClickListener(v -> switchToUpdate());
    }

    // ══════════════════════════════════════════════════════════════
    //  导出安装包（备份当前 APK）
    //  ⭐ 不需要 root：sourceDir 指向本应用自己的 APK，应用自身有读权限。
    //     用途：签名变更 / 换仓库等场景下，卸载重装前先留一份可回退的安装包。
    // ══════════════════════════════════════════════════════════════

    /** 选择导出目录：优先公共 Download（需「所有文件访问」），否则回退应用专属外部目录 */
    private File exportTargetDir() {
        boolean pub = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                pub = android.os.Environment.isExternalStorageManager();
            } catch (Throwable ignored) {
            }
        }
        File base = pub
                ? android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS)
                : getExternalFilesDir(null);
        return base;
    }

    private void exportSelfApk() {
        final File src = new File(getApplicationInfo().sourceDir);
        if (!src.exists()) {
            Toast.makeText(this, "找不到当前安装包路径", Toast.LENGTH_LONG).show();
            return;
        }
        final File base = exportTargetDir();
        if (base == null) {
            Toast.makeText(this, "无法访问存储目录", Toast.LENGTH_LONG).show();
            return;
        }
        if (!base.exists() && !base.mkdirs()) {
            Toast.makeText(this, "无法创建目录：" + base.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        final boolean pub;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                pub = android.os.Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return;
            }
        } else {
            pub = false;
        }
        final File dst = new File(base, "HY_VQ-v" + baseVersionName() + ".apk");
        final boolean exists = dst.exists() && dst.length() > 0;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "导出安装包"));
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.45f);
        tv.setPadding(dp2(4), dp2(4), dp2(4), dp2(4));
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        tv.setText("把当前已安装的 APK 复制出来留档，卸载重装前多一份保障。\n\n"
                + "版本：" + baseVersionName() + "\n"
                + "大小：" + fmtSize(src.length()) + "\n"
                + "目标：" + dst.getAbsolutePath()
                + (exists ? "\n\n⚠️ 目标文件已存在，将被覆盖。" : "")
                + (pub ? "" : "\n\nℹ️ 未授予「所有文件访问」，将导出到应用专属目录，"
                        + "该目录在部分机型上不易访问；建议先授予权限再导出。"));
        box.addView(tv);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        final android.app.Dialog dlg = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("取消", v -> dlg.dismiss()));
        btns.addView(updateTextButton("开始导出", v -> {
            dlg.dismiss();
            doExportSelfApk(src, dst);
        }));
        dlg.show();
    }

    private void doExportSelfApk(final File src, final File dst) {
        final android.app.Dialog progress = new android.app.Dialog(this);
        LinearLayout pb = new LinearLayout(this);
        pb.setOrientation(LinearLayout.VERTICAL);
        pb.setPadding(dp2(24), dp2(24), dp2(24), dp2(24));
        TextView ptv = new TextView(this);
        ptv.setText("正在导出…");
        ptv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        pb.addView(ptv);
        progress.setContentView(pb);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            String err = null;
            long size = 0;
            String md5 = null;
            File tmp = new File(dst.getAbsolutePath() + ".part");
            try {
                try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        size += n;
                    }
                    out.getFD().sync();
                }
                if (size != src.length()) throw new Exception("复制不完整（" + size + "/" + src.length() + "）");
                if (dst.exists() && !dst.delete()) throw new Exception("无法覆盖已存在的文件");
                if (!tmp.renameTo(dst)) throw new Exception("无法写入目标文件");
                md5 = md5Of(dst);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
                try { tmp.delete(); } catch (Throwable ignored) { }
            }
            final String ferr = err;
            final long fsize = size;
            final String fmd5 = md5;
            runOnUiThread(() -> {
                try { progress.dismiss(); } catch (Throwable ignored) { }
                showExportResult(dst, ferr, fsize, fmd5, src.length());
            });
        }, "HyVqExportApk").start();
    }

    private void showExportResult(final File dst, final String err,
                                  final long size, final String md5, final long srcSize) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, err == null ? "导出成功" : "导出失败"));
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.45f);
        tv.setPadding(dp2(4), dp2(4), dp2(4), dp2(4));
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        if (err == null) {
            tv.setText("已保存到：\n" + dst.getAbsolutePath() + "\n\n"
                    + "大小：" + fmtSize(size) + "（与源文件一致 ✓）\n"
                    + "MD5：" + md5 + "\n\n"
                    + "💡 卸载重装前，请先在文件管理器里确认该文件存在且能正常打开。");
        } else {
            tv.setText("原因：" + err + "\n\n目标路径：\n" + dst.getAbsolutePath());
        }
        box.addView(tv);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);
        final android.app.Dialog dlg = ModuleUiKit.glassDialog(this, box);
        if (err == null) {
            btns.addView(updateTextButton("分享", v -> {
                try {
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", dst);
                    android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    it.setType("application/vnd.android.package-archive");
                    it.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                    it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(it, "分享安装包"));
                } catch (Throwable t) {
                    Toast.makeText(this, "分享失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            }));
        }
        btns.addView(updateTextButton("知道了", v -> dlg.dismiss()));
        dlg.show();
    }

    /** 用系统浏览器打开链接 */
    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接：" + url, Toast.LENGTH_SHORT).show();
        }
    }

    /** 开源许可证说明（GPL-3.0 要点 + 跳转全文） */
    private void showLicenseDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "📜 开源许可证"));
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.45f);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(String.join(System.lineSeparator(), new String[]{
                "GNU General Public License v3.0",
                "",
                "你可以自由地：",
                "· 将本软件用于任何目的",
                "· 研究并修改源代码",
                "· 再分发副本",
                "",
                "但必须遵守：",
                "· 公开发布修改后的源码",
                "· 衍生作品同样采用 GPL-3.0",
                "· 保留版权与许可声明",
                "· 作者不提供任何担保"
        }));
        box.addView(tv);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        android.app.Dialog d = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("查看全文", v -> {
            d.dismiss();
            openUrl(OPEN_SOURCE_URL + "/blob/main/LICENSE");
        }));
        btns.addView(updateTextButton("知道了", v -> d.dismiss()));
        box.addView(btns);
        d.show();
    }

    /** 打开文件管理：内置一级功能（非模块） */
    private void openFileManager() {
        if (fileManager == null) {
            fileManager = new FileManagerModule();
            fileManager.onAttach(this, moduleServices);
        }
        View v = fileManager.createMainView(LayoutInflater.from(this), contentFrame);
        if (v == null) return;
        currentModuleId = null;      // 非模块：不参与模块选中态
        switchContent(v, PAGE_FILEMGR);
        resetToolbar();
        binding.toolbarTitle.setText("文件管理 BETA");
        updateDrawerSelection();
    }

    /**
     * 抽卡分析页。
     * <p>粘贴原神祈愿链接 → 解析 authkey → 拉取 API → 按 uid 融合本地存档 → 统计。
     * 页面本身常驻实例，切换回来时保留输入内容与上次结果。</p>
     */
    /**
     * 原神帮助页：含「抽卡分析」入口按钮，点击进入抽卡分析。
     * 侧边栏入口从直连抽卡改为先到本页（2026-10-03）。
     */
    private void switchToGenshinHelp() {
        if (genshinHelpView == null) {
            genshinHelpView = buildGenshinHelpView();
        }
        currentModuleId = null;
        switchContent(genshinHelpView, PAGE_GENSHIN_HELP);
        resetToolbar();
        binding.toolbarTitle.setText("原神帮助");
        updateDrawerSelection();
    }

    /** 构建原神帮助页（程序化 UI，对齐项目卡片风格） */
    private View buildGenshinHelpView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2(14);
        root.setPadding(pad, pad, pad, pad);

        // 标题
        TextView title = new TextView(this);
        title.setText("原神帮助");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp2(4);
        title.setLayoutParams(tlp);
        root.addView(title);

        // 说明
        TextView desc = new TextView(this);
        desc.setText("原神相关的实用工具集合，目前提供祈愿记录分析功能。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp2(16);
        desc.setLayoutParams(dlp);
        root.addView(desc);

        // 抽卡分析入口卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp2(14), dp2(14), dp2(14), dp2(14));
        card.setBackground(ModuleUiKit.rounded(this, 12,
                ModuleUiKit.color(this, com.google.android.material.R.attr.colorSurfaceContainerLow),
                ModuleUiKit.color(this, com.google.android.material.R.attr.colorOutlineVariant)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp2(10);
        card.setLayoutParams(clp);

        // 图标
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_star);
        icon.setColorFilter(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp2(36), dp2(36));
        ilp.rightMargin = dp2(14);
        icon.setLayoutParams(ilp);
        card.addView(icon);

        // 文字区
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cardTitle = new TextView(this);
        cardTitle.setText("抽卡分析");
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cardTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        cardTitle.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurface));
        textCol.addView(cardTitle);

        TextView cardDesc = new TextView(this);
        cardDesc.setText("粘贴祈愿链接，按 UID 融合统计抽卡记录");
        cardDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cardDesc.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        textCol.addView(cardDesc);

        card.addView(textCol);

        // BETA 徽标
        TextView beta = new TextView(this);
        beta.setText("BETA");
        beta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        beta.setPadding(dp2(6), dp2(2), dp2(6), dp2(2));
        beta.setBackground(ModuleUiKit.rounded(this, 6,
                ModuleUiKit.color(this, com.google.android.material.R.attr.colorTertiaryContainer), 0));
        beta.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnTertiaryContainer));
        card.addView(beta);

        // 点击 → 抽卡分析
        card.setOnClickListener(v -> switchToGacha());

        root.addView(card);
        return root;
    }

    private void switchToGacha() {
        if (gachaView == null) {
            gachaView = new com.aliya.hy_vq.gacha.GachaView(this);
            gachaView.showLatestIfAny();
        }
        currentModuleId = null;      // 非模块：不参与模块选中态
        switchContent(gachaView, PAGE_GACHA);
        resetToolbar();
        binding.toolbarTitle.setText("抽卡分析 BETA");
        updateDrawerSelection();
    }

    // ── 动漫：多源聚合搜索 + 按 tier 自动选源播放（内置一级页面）──
    private com.aliya.hy_vq.anime.AnimeView animeView;

    private void switchToAnime() {
        if (animeView == null) {
            animeView = new com.aliya.hy_vq.anime.AnimeView(this);
        }
        currentModuleId = null;      // 非模块：不参与模块选中态
        switchContent(animeView, PAGE_ANIME);
        resetToolbar();
        binding.toolbarTitle.setText("动漫 BETA");
        updateDrawerSelection();
    }

    // ── v2.9.1 内嵌式页面（与文件管理同款：进 contentFrame，不跳独立 Activity）──

    private ShareAppsView shareAppsView;
    private WuwaEmojiView wuwaEmojiView;

    /** 实用软件分享：内嵌视图（复用文件管理那套 switchContent 机制） */
    private void openShareApps() {
        if (shareAppsView == null) {
            shareAppsView = new ShareAppsView(this, LayoutInflater.from(this), contentFrame);
        }
        switchContent(shareAppsView.getRoot(), PAGE_SHARE);
        resetToolbar();
        binding.toolbarTitle.setText("实用软件分享 BETA");
        updateDrawerSelection();
    }

    /** 鸣潮表情包：内嵌视图（原独立 Activity 已改为内嵌） */
    private void openEmoji() {
        if (wuwaEmojiView == null) {
            wuwaEmojiView = new WuwaEmojiView(this, LayoutInflater.from(this), contentFrame);
        }
        switchContent(wuwaEmojiView.getRoot(), PAGE_EMOJI);
        resetToolbar();
        binding.toolbarTitle.setText("鸣潮表情包");
        updateDrawerSelection();
    }

    /** 返回键：内嵌页内部分层（分享页分类内 → 分类列表），再按则回首页 */
    @Override
    public void onBackPressed() {
        if (currentPageIndex == PAGE_SHARE && shareAppsView != null && shareAppsView.onBack()) {
            return;
        }
        if (currentPageIndex == PAGE_SHARE || currentPageIndex == PAGE_EMOJI) {
            switchToHome();
            return;
        }
        super.onBackPressed();
    }

    /** 读取已安装模块记录（loadInstalledModules 用；模块管理 UI 已移除但框架保留） */
    private JSONArray getModuleRecords() {
        String raw = prefs.getString("installed_modules", "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void loadInstalledModules() {
        JSONArray arr = getModuleRecords();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id", "");
                String name = obj.optString("name", "未知模块");
                String mainClass = obj.optString("mainClass", "");
                boolean showInDrawer = obj.optBoolean("showInDrawer", true);
                String installPath = obj.optString("installPath", "");
                if (id.isEmpty() || mainClass.isEmpty() || installPath.isEmpty()) continue;
                // 检查是否已在内存中
                if (moduleRegistry.get(id) != null) continue;
                File dexFile = new File(installPath, "classes.dex");
                if (!dexFile.exists()) {
                    // 文件已丢失，清理记录
                    continue;
                }
                try {
                    DexClassLoader loader = new DexClassLoader(
                        dexFile.getAbsolutePath(),
                        getDir("dexopt", MODE_PRIVATE).getAbsolutePath(),
                        null,
                        getClassLoader()
                    );
                    Class<?> clz = loader.loadClass(mainClass);
                    HyVqModule module = (HyVqModule) clz.newInstance();
                    module.id = id;
                    module.name = name;
                    module.showInDrawer = showInDrawer;
                    // 恢复持久化的启用状态（默认启用）
                    module.enabled = prefs.getBoolean("module_enabled_" + id, true);
                    module.onAttach(this, moduleServices);
                    moduleRegistry.install(module);
                } catch (Exception e) {
                    // 加载失败不影响其他模块
                }
            } catch (JSONException ignored) {}
        }
    }


    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (toggle != null) toggle.syncState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 广播模块生命周期（如聊天模块停止服务）
        moduleRegistry.notifyPause();
    }

    @Override
    protected void onResume() {
        // 从其他页面/后台返回：让文件管理在下次加载时恢复原滚动位置
        if (fileManager != null) fileManager.markReturnToForeground();

        // 授权返回后自动重试导出
        if (pendingExportApk != null) {
            File apk = pendingExportApk;
            String ver = pendingExportVer;
            pendingExportApk = null;
            pendingExportVer = null;
            boolean granted = false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    granted = android.os.Environment.isExternalStorageManager();
                } catch (Throwable ignored) {
                }
            }
            File base = granted
                    ? new File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "HY_VQ")
                    : getExternalFilesDir(null);
            doExportCached(apk, ver, base, "HY_VQ-v" + ver + ".apk");
        }

        super.onResume();
        moduleRegistry.notifyResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 销毁时关闭所有活跃 Dialog，防止窗口泄漏
        // ⭐ 例外：正在等待 onActivityResult 的弹窗（如编辑资料里选头像）必须存活，
        //    否则系统图片选择器一遮挡就会触发 onStop 把弹窗清掉，
        //    用户回来只看到「头像已更新」而弹窗不见了。
        for (Dialog d : activeDialogs) {
            if (d == null || d == dialogAwaitingResult) continue;
            if (d.isShowing()) {
                try { d.dismiss(); } catch (Exception ignored) {}
            }
        }
        // 只清理已关闭的，保留等待结果的那个
        for (int i = activeDialogs.size() - 1; i >= 0; i--) {
            Dialog d = activeDialogs.get(i);
            if (d == null || !d.isShowing()) activeDialogs.remove(i);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (contentFrame != null) {
            for (int i = 0; i < contentFrame.getChildCount(); i++) {
                View child = contentFrame.getChildAt(i);
                if (child != null) child.animate().cancel();
            }
        }
        if (cachedAvatarBmp != null && !cachedAvatarBmp.isRecycled()) {
            cachedAvatarBmp.recycle();
            cachedAvatarBmp = null;
        }
        homeView = null;
        settingsView = null;
        accountView = null;
        aboutView = null;
        updateView = null;
        permissionsView = null;
        hideHintRunnable = null;
        if (themeOverlay != null) {
            themeOverlay.animate().cancel();
            ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
            decorView.removeView(themeOverlay);
            themeOverlay = null;
        }
        // 通知所有模块销毁
        moduleRegistry.detachAll();
        binding = null;
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (toggle != null) toggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (toggle != null && toggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== Toolbar ====================
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        // 自定义返回按钮：所有返回入口统一走 goBack()
        binding.btnBackCustom.setVisibility(View.GONE);
        binding.btnBackCustom.setOnClickListener(v -> goBack());
    }



    private void setToolbarTitle(String title) {
        binding.toolbarTitle.setText(title);
    }

    private void resetToolbar() {
        binding.btnBackCustom.setVisibility(View.GONE);
        binding.btnBackCustom.setOnClickListener(null);
        binding.btnMenuHome.setVisibility(View.GONE);
        binding.toolbarTitle.setText("HY_VQ");
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        if (toggle != null) toggle.setDrawerIndicatorEnabled(true);
    }

    private void setSubpageToolbar(String title) {
        if (toggle != null) toggle.setDrawerIndicatorEnabled(false);
        binding.btnBackCustom.setVisibility(View.VISIBLE);
        binding.btnBackCustom.setOnClickListener(v -> goBack());
        binding.btnMenuHome.setVisibility(View.GONE);
        binding.toolbarTitle.setText(title);
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    /**
     * 统一返回逻辑：抽屉打开时先关抽屉；
     * 子页（账号/个性化/模块设置）→ 设置；模块页 → 首页；
     * 设置页 → 首页；其余交给系统默认返回。
     *
     * @return true 表示已消费返回事件
     */
    private boolean goBack() {
        if (binding == null) return false;
        if (binding.drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            binding.drawerLayout.closeDrawers();
            return true;
        }
        if (accountView != null && accountView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (aboutView != null && aboutView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (updateView != null && updateView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (permissionsView != null && permissionsView.getParent() != null) {
            switchToSettings();
            return true;
        }
        if (currentModuleId != null) {
            switchToHome();
            return true;
        }
        if (currentPageIndex == PAGE_SETTINGS) {
            switchToHome();
            return true;
        }
        return false;
    }

    // ==================== Drawer ====================
    private void setupDrawer() {
        toggle = new ActionBarDrawerToggle(
                this, binding.drawerLayout, binding.toolbar,
                R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        binding.drawerLayout.addDrawerListener(toggle);
        binding.drawerLayout.setScrimColor(0x33000000);

        // 自定义侧边栏宽度（屏幕宽度的一半）
        DrawerLayout.LayoutParams params = (DrawerLayout.LayoutParams) binding.navView.getLayoutParams();
        params.width = getResources().getDisplayMetrics().widthPixels / 2;
        binding.navView.setLayoutParams(params);

        // 抽屉打开时在内容区加触摸拦截，防止文件管理的滑动/拖拽监听
        // 与 DrawerLayout 遮罩竞争导致概率性点击穿透
        binding.drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (contentFrame != null) {
                    contentFrame.setClickable(true);
                    contentFrame.setFocusable(true);
                    contentFrame.setOnTouchListener((v, ev) -> true); // 消费一切
                }
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                if (contentFrame != null) {
                    contentFrame.setClickable(false);
                    contentFrame.setFocusable(false);
                    contentFrame.setOnTouchListener(null);
                }
                ViewGroup blurTarget = contentFrame != null ? (ViewGroup) contentFrame.getParent() : null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurTarget != null) {
                    blurTarget.setRenderEffect(null);
                }
            }

            @Override
            public void onDrawerSlide(View drawerView, float offset) {
                // 侧边栏滑出时对主内容区动态模糊（Android 12+）
                ViewGroup blurTarget = contentFrame != null ? (ViewGroup) contentFrame.getParent() : null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurTarget != null) {
                    float radius = offset * 25f;
                    if (radius > 0.5f) {
                        blurTarget.setRenderEffect(
                            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
                    } else {
                        blurTarget.setRenderEffect(null);
                    }
                }
            }
        });
        // 底部全面屏手势条适配：侧边栏底部内收避免被手势条遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
        // 头部引用
        View headerView = binding.navView.findViewWithTag("header");
        if (headerView == null) {
            // include 标签内的视图直接从 navView 中查找
            drawerUsername = binding.navView.findViewById(R.id.tv_username);
            drawerAvatar = binding.navView.findViewById(R.id.iv_avatar);
            TextView uidView = binding.navView.findViewById(R.id.tv_uid);
            if (uidView != null && signatureManager != null) {
                uidView.setText("UID: " + signatureManager.getUid());
            }
            if (drawerAvatar != null) {
                drawerAvatar.setOnClickListener(v -> {
                    binding.drawerLayout.closeDrawers();
                    binding.drawerLayout.postDelayed(this::showEditProfileDialog, 160);
                });
            }
        }
        loadAvatar(drawerAvatar);
    }

    private void updateDrawerUsername() {
        if (drawerUsername != null) {
            drawerUsername.setText(signatureManager.getUsername());
        }
    }

    private void setupDrawerNavigation() {
        // 首页
        binding.navView.findViewById(R.id.nav_home).setOnClickListener(v -> {
            binding.drawerLayout.closeDrawers();
            binding.drawerLayout.postDelayed(this::switchToHome, 160);
        });
        // 鸣潮表情包（数据来源：呜哇小站 emoji.wuwa.games）
        View emojiBtn = binding.navView.findViewById(R.id.nav_emoji);
        if (emojiBtn != null) {
            emojiBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::openEmoji, 160);
            });
        }
        // 实用软件分享（v2.9.0：只读浏览 123 云盘分享目录）
        View shareBtn = binding.navView.findViewById(R.id.nav_share);
        if (shareBtn != null) {
            shareBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::openShareApps, 160);
            });
        }
        // 文件管理（内置一级功能，非模块）
        View fmBtn = binding.navView.findViewById(R.id.nav_filemgr);
        if (fmBtn != null) {
            fmBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::openFileManager, 160);
            });
        }
        // 原神帮助（含抽卡分析入口）
        View gachaBtn = binding.navView.findViewById(R.id.nav_gacha);
        if (gachaBtn != null) {
            gachaBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::switchToGenshinHelp, 160);
            });
        }
        // 动漫（多源聚合搜索）
        View animeBtn = binding.navView.findViewById(R.id.nav_anime);
        if (animeBtn != null) {
            animeBtn.setOnClickListener(v -> {
                binding.drawerLayout.closeDrawers();
                binding.drawerLayout.postDelayed(this::switchToAnime, 160);
            });
        }
        // 设置（底部按钮）
        binding.navView.findViewById(R.id.nav_settings).setOnClickListener(v -> {
            binding.drawerLayout.closeDrawers();
            binding.drawerLayout.postDelayed(this::switchToSettings, 160);
        });
    }

    // ==================== Avatar ====================
    private Bitmap getCircleBitmap(Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        Bitmap squared = Bitmap.createBitmap(src, x, y, size, size);
        if (squared != src) src.recycle();

        Bitmap output = null;
        try {
            output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Rect rect = new Rect(0, 0, size, size);
            RectF rectF = new RectF(rect);
            canvas.drawOval(rectF, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(squared, rect, rect, paint);
        } catch (Exception e) {
            if (output != null && !output.isRecycled()) {
                output.recycle();
            }
            output = null;
        } finally {
            // 确保 squared 在异常路径下也能被回收
            if (squared != null && !squared.isRecycled()) {
                squared.recycle();
            }
        }
        return output;
    }

    /** 缓存已解码的头像 Bitmap，避免重复解码和内存泄漏 */
    private Bitmap cachedAvatarBmp;
    private final Object avatarLock = new Object();

    private void loadAvatar(ImageView target) {
        if (target == null) return;
        target.clearColorFilter();
        target.setImageTintList(null);
        if (avatarPath != null && !avatarPath.isEmpty() && new File(avatarPath).exists()) {
            synchronized (avatarLock) {
                if (cachedAvatarBmp == null || cachedAvatarBmp.isRecycled()) {
                    cachedAvatarBmp = BitmapFactory.decodeFile(avatarPath);
                }
                if (cachedAvatarBmp != null) {
                    target.setImageBitmap(cachedAvatarBmp);
                    return;
                }
            }
        }
        target.setImageResource(R.drawable.ic_mine);
        target.setColorFilter(resolveAttr(com.google.android.material.R.attr.colorPrimary));
    }

    private void refreshAllAvatars() {
        loadAvatar(drawerAvatar);
        loadAvatar(editAvatar);
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_PICK_AVATAR);
    }

    private void saveAvatar(Uri uri) {
        try {
            Bitmap raw;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                raw = BitmapFactory.decodeStream(in);
            }
            if (raw == null) {
                Toast.makeText(this, "无法读取图片", Toast.LENGTH_SHORT).show();
                return;
            }
            Bitmap circle = getCircleBitmap(raw);

            File destFile = new File(getFilesDir(), "avatar.png");
            try (FileOutputStream out = new FileOutputStream(destFile)) {
                circle.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            circle.recycle();
            avatarPath = destFile.getAbsolutePath();
            prefs.edit().putString("avatar_path", avatarPath).apply();
            // 刷新头像缓存：释放旧 Bitmap，让下次 loadAvatar 重新解码新图片
            synchronized (avatarLock) {
                if (cachedAvatarBmp != null && !cachedAvatarBmp.isRecycled()) {
                    cachedAvatarBmp.recycle();
                }
                cachedAvatarBmp = null;
            }
            refreshAllAvatars();
            Toast.makeText(this, "头像已更新", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存头像失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Edit Profile Dialog ====================
    private void showEditProfileDialog() {
        // ⭐ v2.9.4 弹窗统一：与全项目同源的 glassDialog（玻璃面板 + 描边 + 模糊 + 点阴影处关闭）。
        // 此前是手搓 Dialog：自设窗口宽高与模糊，且**没有**点外关闭。
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null);
        final Dialog dialog = ModuleUiKit.glassDialog(this, v, true);

        ImageView dialogAvatar = v.findViewById(R.id.dialog_avatar);
        EditText dialogName = v.findViewById(R.id.dialog_name);
        EditText dialogSignature = v.findViewById(R.id.dialog_signature);
        TextView dialogSaveHint = v.findViewById(R.id.dialog_save_hint);

        // 预填数据
        dialogName.setText(signatureManager.getUsername());
        dialogSignature.setText(prefs.getString("signature", ""));
        dialogSaveHint.setVisibility(View.GONE);
        loadAvatar(dialogAvatar);

        // 头像点击
        dialogAvatar.setOnClickListener(w -> {
            editAvatar = dialogAvatar; // 暂存引用供 onActivityResult 刷新
            // ⭐ 标记本弹窗正在等待结果：系统图片选择器遮挡会触发 onStop，
            //    若不豁免，弹窗会被当泄漏窗口销毁，用户回来只看到头像变了、弹窗没了。
            dialogAwaitingResult = dialog;
            pickAvatar();
        });

        // 取消
        v.findViewById(R.id.dialog_btn_cancel).setOnClickListener(w -> dialog.dismiss());

        // 保存
        v.findViewById(R.id.dialog_btn_save).setOnClickListener(w -> {
            String newName = dialogName.getText().toString().trim();
            String newSig = dialogSignature.getText().toString().trim();
            boolean changed = false;
            if (!newName.isEmpty()) {
                if (drawerUsername != null) drawerUsername.setText(newName);
                prefs.edit().putString("username", newName).apply();
                if (signatureManager != null) signatureManager.updateUsername(newName);
                changed = true;
            }
            if (!newSig.isEmpty()) {
                prefs.edit().putString("signature", newSig).apply();
                changed = true;
            }
            if (changed) {
                dialogSaveHint.setVisibility(View.VISIBLE);
                if (hideHintRunnable != null) handler.removeCallbacks(hideHintRunnable);
                hideHintRunnable = () -> dialogSaveHint.setVisibility(View.GONE);
                handler.postDelayed(hideHintRunnable, 3000);
            }
        });

        dialog.setOnDismissListener(d -> {
            activeDialogs.remove(dialog);
            editAvatar = null; // 释放弹窗头像引用
            if (dialogAwaitingResult == dialog) dialogAwaitingResult = null; // 解除保护，避免悬空引用
            if (toggle != null) toggle.setDrawerIndicatorEnabled(true);
        });
        activeDialogs.add(dialog);
        dialog.show();

        if (toggle != null) toggle.setDrawerIndicatorEnabled(false);
    }

    private void showSignatureDialog() {
        if (signatureManager == null) return;
        // ⭐ v2.9.4 弹窗统一：同 ③（旧背景图 bg_dialog_add_friend + 自设窗口 + 无点外关闭）
        final View v = LayoutInflater.from(this).inflate(R.layout.dialog_signature, null);
        final Dialog dialog = ModuleUiKit.glassDialog(this, v, true);
        TextView tvSig = v.findViewById(R.id.tv_signature);
        try {
            JSONObject sigObj = new JSONObject(signatureManager.getSignatureJson());
            tvSig.setText("用户名：" + sigObj.getString("username") + "\n"
                    + "UID：" + sigObj.getString("uid") + "\n"
                    + "UUID：" + sigObj.getString("uuid"));
        } catch (JSONException e) {
            tvSig.setText("签名数据异常");
        }
        v.findViewById(R.id.btn_copy).setOnClickListener(w -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("uid", signatureManager.getUid()));
            Toast.makeText(this, "UID 已复制", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btn_copy_uuid).setOnClickListener(w -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("uuid", signatureManager.getUuid()));
            Toast.makeText(this, "UUID 已复制", Toast.LENGTH_SHORT).show();
        });
        dialog.setOnDismissListener(d -> activeDialogs.remove(dialog));
        activeDialogs.add(dialog);
        dialog.show();
    }

    private int resolveAttr(int attrRes) {
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(attrRes, typedValue, true);
        return typedValue.data;
    }

    // ==================== Page Switching ====================
    private void switchContent(View newView, int newPageIndex) {
        View oldView = contentFrame.getChildCount() > 0 ? contentFrame.getChildAt(0) : null;
        if (oldView == newView) return;

        if (oldView != null) oldView.animate().cancel();
        newView.animate().cancel();

        if (newView.getParent() instanceof ViewGroup) {
            ((ViewGroup) newView.getParent()).removeView(newView);
        }

        int width = contentFrame.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;

        // 同页跳转不播放平移动画
        boolean samePage = newPageIndex == currentPageIndex;
        float fromX = samePage ? 0 : (newPageIndex < currentPageIndex ? -width * 0.25f : width * 0.25f);

        contentFrame.removeAllViews();
        contentFrame.addView(newView);

        if (!samePage) {
            newView.setTranslationX(fromX);
            newView.setAlpha(0.4f);
            newView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(SI_EXP_EASE)
                    .start();
        }

        currentPageIndex = newPageIndex;
        // 切回固定页时清除模块页状态，保证返回逻辑正确
        if (newPageIndex < PAGE_MODULE_BASE) {
            currentModuleId = null;
        }
        // 同步侧边栏选中态
        updateDrawerSelection();
    }

    private void switchToHome() {
        if (homeView == null) {
            homeView = LayoutInflater.from(this).inflate(R.layout.fragment_home, contentFrame, false);
            setupHomeView();
        }
        switchContent(homeView, PAGE_HOME);
        resetToolbar();
    }

    private void setupHomeView() {
        // 「软件分享」卡片（v2.9.1）：内嵌打开，不再跳独立页面
        homeView.findViewById(R.id.card_explore).setOnClickListener(v -> openShareApps());
        homeView.findViewById(R.id.card_moments).setOnClickListener(v ->
                Toast.makeText(this, "朋友圈功能即将上线", Toast.LENGTH_SHORT).show());
        homeView.findViewById(R.id.card_mine).setOnClickListener(v -> switchToAccount());
        homeView.findViewById(R.id.btn_start).setOnClickListener(v -> openFileManager());
        homeView.findViewById(R.id.btn_about).setOnClickListener(v ->
                Toast.makeText(this, "HY_VQ - 极简高效连接", Toast.LENGTH_SHORT).show());
        // 更新包入口横幅注入（验证"更新包代码注入应用 UI"链路）
        try {
            HyVqAppEntry entry = UpdateManager.entry();
            if (entry != null && homeView instanceof android.widget.ScrollView) {
                View banner = entry.createHomeBanner(this);
                if (banner != null) {
                    android.widget.ScrollView scroll = (android.widget.ScrollView) homeView;
                    if (scroll.getChildCount() > 0 && scroll.getChildAt(0) instanceof LinearLayout) {
                        LinearLayout inner = (LinearLayout) scroll.getChildAt(0);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        inner.addView(banner, 0, lp);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w("HYVQ", "home banner inject failed", t);
        }
    }

    private void switchToSettings() {
        if (settingsView == null) {
            settingsView = LayoutInflater.from(this).inflate(R.layout.fragment_settings, contentFrame, false);
            setupSettingsView();
        }
        // 每次进入时刷新动态字段
        TextView tvDefault = settingsView.findViewById(R.id.tv_default_page);
        if (tvDefault != null) {
            String currentDefault = prefs.getString("default_page", "home");
            tvDefault.setText("首页");
        }
        // 软件更新状态行（更新包版本随时可能变化，进入时刷新）
        updateUpdateStateLabel();
        switchContent(settingsView, PAGE_SETTINGS);
        resetToolbar();
        binding.toolbarTitle.setText("设置");
    }

    private void setupSettingsView() {
        // 刷新默认启动页显示
        TextView tvDefault = settingsView.findViewById(R.id.tv_default_page);
        String currentDefault = prefs.getString("default_page", "home");
        tvDefault.setText("home".equals(currentDefault) ? "首页" : "聊天");

        // 账号管理行标签：显示当前用户名（该页已上线，不再标"开发中"）
        TextView tvAccountLabel = settingsView.findViewById(R.id.tv_account_label);
        if (tvAccountLabel != null && signatureManager != null) {
            tvAccountLabel.setText(signatureManager.getUsername());
        }

        settingsView.findViewById(R.id.item_account).setOnClickListener(v -> switchToAccount());
        settingsView.findViewById(R.id.item_general).setOnClickListener(v -> showDefaultPageDialog());
        View itemAbout = settingsView.findViewById(R.id.item_about);
        if (itemAbout != null) itemAbout.setOnClickListener(v -> switchToAbout());
        refreshStorageLabel();
        TextView tvAboutLabel = settingsView.findViewById(R.id.tv_about_label);
        if (tvAboutLabel != null) tvAboutLabel.setText("v" + baseVersionName());
        settingsView.findViewById(R.id.item_storage).setOnClickListener(v -> showStorageManagerDialog());
        // 显示密度（应用级 DPI 覆盖）
        View itemDpi = settingsView.findViewById(R.id.item_dpi);
        if (itemDpi != null) itemDpi.setOnClickListener(v -> showDpiDialog());
        refreshDpiLabel();
        // 权限管理入口（检查各项权限申请情况）
        settingsView.findViewById(R.id.item_permission).setOnClickListener(v -> switchToPermissions());
        // 软件更新入口（增量更新包导入中心）
        View itemUpdate = settingsView.findViewById(R.id.item_update);
        if (itemUpdate != null) itemUpdate.setOnClickListener(v -> switchToUpdate());
        settingsView.findViewById(R.id.item_logout).setOnClickListener(v -> {
            // 仅清除会话标记，保留身份数据（uid/uuid），重新登录可恢复原身份
            signatureManager.clearSession();
            // 跳回登录页
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    // ==================== 软件更新（增量更新包本地导入） ====================

    /** 壳版本名（运行时读取，避免依赖 BuildConfig 生成开关） */
    private String baseVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** 刷新设置页"软件更新"行状态文本 */
    /** 更新中心按钮（ModuleUiKit 风格纯代码构建） */
    private TextView updateTextButton(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorPrimary));
        tv.setGravity(Gravity.CENTER);
        int pad = dp2(12);
        tv.setPadding(pad, pad, pad, pad);
        tv.setOnClickListener(onClick);
        return tv;
    }

    // ── 远程更新（GitHub 公开仓库只读直链）──────────────────────
    // 2026-09-06 变更：更新源从自建 WebDAV 迁到 GitHub —— 公开仓库的 raw 文件与
    // release 资产都是**无鉴权直链**，天然只读，APK 内不再内置任何账号密码。
    // 2026-09-27：项目迁至新账号 haoyou781013（旧账号双重验证密钥随恢复出厂设置丢失，
    // 无法登录也无法重置，详见 MIGRATION.md）。旧的 haoyou999/HY_VQ 保留只读，
    // 其 raw 直链与已发布的 Release 资产继续有效，不影响老版本客户端的更新检查。
    private static final String GH_OWNER = "haoyou781013";
    /** 更新源与源码同仓：APK 走该仓的 Release 资产 */
    private static final String GH_REPO = "HY_VQ";
    /** 启动时自动检查更新的偏好键（默认开启） */
    private static final String PREF_AUTO_CHECK_UPDATE = "auto_check_update";
    /** 国内备用更新源：123 云盘 WebDAV（内置**只读**凭据，泄漏也无法写入） */
    private static final String CN_BASE = "https://webdav.123pan.cn/webdav/HY_VQ-updates/";
    private static final String CN_VERSIONS = CN_BASE + "versions.json";
    private static final String CN_HOST = "webdav.123pan.cn";
    /** GitHub 直链的国内加速镜像（依次尝试；不需要任何凭据） */
    private static final String[] GH_MIRRORS = {
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
    };
    /** 只读账密（与发布端使用的读写账密是同一账号的不同密码） */
    // ⭐ 这里刻意使用**只读**凭据：客户端只需要下载更新包，不需要写入。
    // 即使该密码泄露，攻击者也无法篡改国内源的 APK（写操作返回 403）。
    // 发布脚本用的是另一套读写凭据，两者互不通用。
    // 注：网盘密码变更后此处需同步更新；客户端另有 CredentialStore 持久化，
    // 若持久化的是旧值，openRemote 的 401 分支会自动清除并回退到本常量。
    private static final String CN_CRED_SEED = CredentialStore.READONLY_SEED;

    /** 版本列表来源：GitHub Releases API —— 一次请求拿到全部版本，
     *  每个版本自带<b>精确</b>下载直链（browser_download_url）。
     *  不再依赖 latest.json，也不会出现「latest 前缀 + 旧文件名」导致的 404。 */
    private static final String REMOTE_RELEASES =
            "https://api.github.com/repos/" + GH_OWNER + "/" + GH_REPO + "/releases?per_page=100";

    /** 打开远程只读连接（无鉴权）。
     *  某些 CDN 会 302 跳转，故手动跟随（最多 5 跳），不依赖 HttpURLConnection 自动跟随。
     *  返回的连接已带最终响应码，调用方负责读流与 disconnect()。 */
    /**
     * 把 GitHub 直链包上加速镜像前缀。
     * <p>GitHub 的 release 资产实际托管在 objects.githubusercontent.com，国内常被阻断 ——
     * 直链失败会自动回退到 123云盘，而网盘需要凭据（曾因内置密码过时导致 401）。
     * 镜像列为公开通道，用它可绕开凭据问题。</p>
     */
    private static String mirrorUrl(String mirrorPrefix, String url) {
        if (url == null || url.isEmpty()) return url;
        if (!url.startsWith("https://github.com/")
                && !url.startsWith("https://objects.githubusercontent.com/")) {
            return null;   // 只镜像 GitHub 源
        }
        return mirrorPrefix + url;
    }

    private java.net.HttpURLConnection openRemote(String url, String method) throws Exception {
        return openRemote(url, method, null);
    }

    private java.net.HttpURLConnection openRemote(String url, String method, String accept) throws Exception {
        credRetried = false;   // 每次新请求重置 401 重试标记
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(cur).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", accept != null ? accept
                    : "application/json, application/octet-stream, */*");
            // 国内备用源需要 Basic 鉴权（只读凭据）；GitHub 侧保持零凭据
            if (cur.contains(CN_HOST) && this.cachedCnCred == null) {
                String c = CredentialStore.load(this);
                if (c == null) {
                    CredentialStore.save(this, CN_CRED_SEED);
                    c = CredentialStore.load(this);
                }
                this.cachedCnCred = c == null ? "" : c;
            }
            if (cur.contains(CN_HOST) && this.cachedCnCred != null && !this.cachedCnCred.isEmpty()) {
                conn.setRequestProperty("Authorization", "Basic "
                        + android.util.Base64.encodeToString(
                        this.cachedCnCred.getBytes("UTF-8"), android.util.Base64.NO_WRAP));
            }
            int code = conn.getResponseCode();
            // ⭐ 401：网盘凭据可能已轮换（密码在服务端改过）。
            // 持久化的旧值会一直覆盖内置 seed，导致永远 401 ——
            // 故此处清掉持久化值，改用内置 CN_CRED_SEED 重试一次。
            if (code == 401 && cur.contains(CN_HOST) && !credRetried) {
                credRetried = true;
                CredentialStore.clear(this);
                this.cachedCnCred = CN_CRED_SEED;
                conn.disconnect();
                continue;   // 回到 hop 循环重建请求（这次带新凭据）
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new Exception("重定向缺少 Location 头");
                if (!loc.startsWith("http")) loc = new java.net.URL(new java.net.URL(cur), loc).toString();
                cur = loc;
                continue;
            }
            return conn;
        }
        throw new Exception("重定向次数过多");
    }

    // ==================== 软件更新页 ====================

    /** 一个可用版本（来自 GitHub Releases API） */
    private static class ReleaseInfo {
        String tag = "";
        String ver = "";
        String body = "";
        String publishedAt = "";
        String apkName = "";
        String apkUrl = "";     // GitHub 精确下载直链
        String apkCnUrl = "";   // 国内备用直链（123 云盘，可为空）
        long apkSize = 0L;

        String date() {
            return publishedAt.length() >= 10 ? publishedAt.substring(0, 10) : publishedAt;
        }
    }

    /** 语义化版本比较：a > b 返回正数 */
    private static int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.split("[.]");
        String[] y = b.split("[.]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v.trim().replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private void switchToUpdate() {
        if (updateView == null) {
            updateView = LayoutInflater.from(this).inflate(R.layout.fragment_update, contentFrame, false);
            setupUpdateView();
        }
        renderUpdateView();
        switchContent(updateView, PAGE_UPDATE);
        setSubpageToolbar("软件更新");
    }

    private void setupUpdateView() {
        if (updateView == null) return;
        updateView.findViewById(R.id.btn_upd_check).setOnClickListener(v -> checkRemoteUpdate());
        updateView.findViewById(R.id.btn_upd_action).setOnClickListener(v -> onUpdateAction());
        updateView.findViewById(R.id.btn_upd_install_cached).setOnClickListener(v -> {
            if (updCachedApk != null) installApk(updCachedApk);
        });
        // 整行与按钮都能切换历史版本展开（按钮自带水波纹反馈）
        View.OnClickListener toggleHistory = v -> {
            historyExpanded = !historyExpanded;
            renderReleaseList(true);      // true = 启用过渡动画
        };
        View histHeader = updateView.findViewById(R.id.row_history_header);
        if (histHeader != null) histHeader.setOnClickListener(toggleHistory);
        View btnHistoryToggle = updateView.findViewById(R.id.btn_history_toggle);
        if (btnHistoryToggle != null) btnHistoryToggle.setOnClickListener(toggleHistory);
        com.google.android.material.switchmaterial.SwitchMaterial swAuto =
                updateView.findViewById(R.id.switch_upd_auto);
        TextView tvAutoDesc = updateView.findViewById(R.id.tv_upd_auto_desc);
        if (swAuto != null) {
            boolean on = prefs.getBoolean(PREF_AUTO_CHECK_UPDATE, true);
            swAuto.setOnCheckedChangeListener(null);
            swAuto.setChecked(on);
            if (tvAutoDesc != null) {
                tvAutoDesc.setText(on
                        ? "打开应用时在后台静默检查，发现新版本会提示"
                        : "已关闭，需手动点击「检查更新」");
            }
            swAuto.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean(PREF_AUTO_CHECK_UPDATE, checked).apply();
                if (tvAutoDesc != null) {
                    tvAutoDesc.setText(checked
                            ? "打开应用时在后台静默检查，发现新版本会提示"
                            : "已关闭，需手动点击「检查更新」");
                }
        // 导出安装包（由关于页迁入）
        View itemUpdExport = updateView.findViewById(R.id.item_upd_export);
        if (itemUpdExport != null) itemUpdExport.setOnClickListener(v -> exportSelfApk());

        // 更新提醒（原设置页「通知管理」并入此处，避免职责重叠）
        LinearLayout boxNotify = updateView.findViewById(R.id.box_upd_notify);
        if (boxNotify != null) {
            boxNotify.removeAllViews();
            addNotifySwitch(boxNotify, PREF_NOTIFY_NEW_VERSION, true,
                    "发现新版本时提示", "检查到新版本后弹窗询问是否更新");
            addNotifySwitch(boxNotify, PREF_NOTIFY_DOWNLOAD_DONE, true,
                    "下载完成时提示", "更新包下载并校验完成后提醒");
        }
            });
        }
        TextView help = updateView.findViewById(R.id.tv_upd_help);
        if (help != null) {
            help.setText(String.join(System.lineSeparator(), new String[]{
                    "· 从公开更新源获取版本清单，在线下载安装包",
                    "· 下载完成自动校验 MD5，通过后交由系统安装器安装",
                    "· 安装包会缓存在本机，再次进入本页可直接安装，无需重复下载",
                    "· 更新不会影响书签、设置与浏览偏好"
            }));
        }
    }

    private int currentPkgCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 统一渲染更新页内容：状态卡 / 更新日志卡 / 缓存卡 / 操作按钮 */
    /** 统一渲染更新页：当前版本 / 状态卡 / 可用版本列表 / 缓存卡 / 操作按钮 */
    private void renderUpdateView() {
        if (updateView == null) return;
        final int cur = currentPkgCode();
        final String curVer = baseVersionName();

        TextView tvCur = updateView.findViewById(R.id.tv_upd_current);
        if (tvCur != null) tvCur.setText("v" + curVer);
        TextView tvChan = updateView.findViewById(R.id.tv_upd_channel);
        if (tvChan != null) tvChan.setText("版本号 " + cur + " · 稳定版");

        // 缓存检测：本机是否已存有更新的安装包（原生安装器不会清理缓存包）
        File[] newer = findCachedNewerApks(cur);
        updCachedApk = null;
        if (newer.length > 0) {
            File best = newer[0];
            for (File f : newer) if (f.lastModified() > best.lastModified()) best = f;
            updCachedApk = best;
        }

        final ReleaseInfo newest = updReleases.isEmpty() ? null : updReleases.get(0);
        final boolean hasNew = newest != null && compareVersion(newest.ver, curVer) > 0;
        final boolean canInstall = hasNew || updCachedApk != null;

        // ── 状态卡 ──
        TextView title = updateView.findViewById(R.id.tv_upd_status_title);
        TextView desc = updateView.findViewById(R.id.tv_upd_status_desc);
        ImageView icon = updateView.findViewById(R.id.iv_upd_status_icon);
        if (updState == 1) {
            if (title != null) title.setText("正在扫描版本…");
            if (desc != null) desc.setText("正在获取全部可用版本，请稍候");
            if (icon != null) icon.setImageResource(R.drawable.ic_refresh);
        } else if (updState == 4) {
            if (title != null) title.setText("获取版本列表失败");
            if (desc != null) desc.setText(updError.isEmpty() ? "无法连接更新源，请检查网络后重试" : updError);
            if (icon != null) icon.setImageResource(R.drawable.ic_network);
        } else if (hasNew) {
            if (title != null) title.setText("发现新版本 v" + newest.ver);
            if (desc != null) desc.setText("已扫描到 " + updReleases.size() + " 个版本，可在下方选择任意版本安装");
            if (icon != null) icon.setImageResource(R.drawable.ic_download);
        } else if (updCachedApk != null) {
            if (title != null) title.setText("可安装 " + cachedApkLabel(updCachedApk));
            if (desc != null) desc.setText("安装包已在本机缓存，无需重新下载");
            if (icon != null) icon.setImageResource(R.drawable.ic_download);
        } else if (updState == 2) {
            if (title != null) title.setText("已是最新版本");
            if (desc != null) desc.setText("已扫描 " + updReleases.size() + " 个版本，下方可查看或重装任意版本");
            if (icon != null) icon.setImageResource(R.drawable.ic_star);
        } else {
            if (title != null) title.setText("检查更新");
            if (desc != null) desc.setText("点「检查更新」扫描全部可用版本");
            if (icon != null) icon.setImageResource(R.drawable.ic_refresh);
        }

        renderReleaseList();

        // ── 缓存卡 ──
        View cacheCard = updateView.findViewById(R.id.card_upd_cache);
        if (cacheCard != null) {
            if (updCachedApk != null) {
                cacheCard.setVisibility(View.VISIBLE);
                TextView ci = updateView.findViewById(R.id.tv_upd_cache_info);
                if (ci != null) {
                    ci.setText(cachedApkLabel(updCachedApk) + System.lineSeparator()
                            + "大小 " + fmtSize(updCachedApk.length()) + " · 已通过完整性校验");
                }
            } else {
                cacheCard.setVisibility(View.GONE);
            }
        }

        // ── 操作按钮 ──
        View btnCheck = updateView.findViewById(R.id.btn_upd_check);
        if (btnCheck != null) btnCheck.setEnabled(updState != 1);
        View btnAction = updateView.findViewById(R.id.btn_upd_action);
        if (btnAction != null) {
            if (canInstall) {
                btnAction.setVisibility(View.VISIBLE);
                ((com.google.android.material.button.MaterialButton) btnAction).setText(
                        hasNew ? "更新到 v" + newest.ver : "立即安装（使用缓存）");
            } else {
                btnAction.setVisibility(View.GONE);
            }
        }
        updateUpdateStateLabel();
    }

    /** 渲染「可用版本」列表：每行可点，下载直链取自 API 返回的精确地址 */
    /** 渲染版本区：最新版本 / 当前版本 / 历史版本（默认折叠，避免列表过长） */
    private void renderReleaseList() {
        renderReleaseList(false);
    }

    private void renderReleaseList(boolean animate) {
        if (updateView == null) return;
        LinearLayout boxLatest = updateView.findViewById(R.id.box_latest_version);
        LinearLayout boxCurrent = updateView.findViewById(R.id.box_current_version);
        LinearLayout boxHistory = updateView.findViewById(R.id.box_history_list);
        View histHeader = updateView.findViewById(R.id.row_history_header);
        com.google.android.material.button.MaterialButton btnToggle =
                updateView.findViewById(R.id.btn_history_toggle);
        if (boxLatest == null || boxCurrent == null || boxHistory == null) return;

        boxLatest.removeAllViews();
        boxCurrent.removeAllViews();
        boxHistory.removeAllViews();

        final String curVer = baseVersionName();

        // 尚未扫描：给出提示，并仍列出当前版本（本地信息，无需联网）
        if (updReleases.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("点「检查更新」获取版本信息");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(ModuleUiKit.color(this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            int pad = dp2(4);
            tv.setPadding(pad, dp2(8), pad, pad);
            boxLatest.addView(tv);

            ReleaseInfo local = new ReleaseInfo();
            local.ver = curVer;
            boxCurrent.addView(buildReleaseRow(local, true));
            if (histHeader != null) histHeader.setVisibility(View.GONE);
            return;
        }

        ReleaseInfo latest = updReleases.get(0);
        ReleaseInfo current = null;
        java.util.List<ReleaseInfo> history = new java.util.ArrayList<>();
        for (ReleaseInfo ri : updReleases) {
            if (compareVersion(ri.ver, latest.ver) == 0) continue;
            if (compareVersion(ri.ver, curVer) == 0) {
                current = ri;
                continue;
            }
            history.add(ri);
        }

        boxLatest.addView(buildReleaseRow(latest, compareVersion(latest.ver, curVer) == 0));
        if (current != null) {
            boxCurrent.addView(buildReleaseRow(current, true));
        } else if (compareVersion(latest.ver, curVer) == 0) {
            // 当前版本 = 最新版：上面的循环跳过了 latest，直接复用它（含 body）
            boxCurrent.addView(buildReleaseRow(latest, true));
        } else {
            ReleaseInfo local = new ReleaseInfo();
            local.ver = curVer;
            boxCurrent.addView(buildReleaseRow(local, true));
        }

        if (history.isEmpty()) {
            if (histHeader != null) histHeader.setVisibility(View.GONE);
            boxHistory.setVisibility(View.GONE);
        } else {
            if (histHeader != null) histHeader.setVisibility(View.VISIBLE);
            for (ReleaseInfo ri : history) boxHistory.addView(buildReleaseRow(ri, false));

            // 展开/收起时启用平滑过渡：必须在改变可见性**之前**启动，
            // AutoTransition 会自动处理淡入淡出与高度变化（系统 API，无需额外依赖）
            if (animate) {
                ViewGroup scene = updateView.findViewById(R.id.update_content);
                if (scene != null) {
                    android.transition.TransitionManager.beginDelayedTransition(
                            scene, new android.transition.AutoTransition().setDuration(240));
                }
            }
            boxHistory.setVisibility(historyExpanded ? View.VISIBLE : View.GONE);

            if (btnToggle != null) {
                btnToggle.setText(historyExpanded ? "收起" : "展开");
                btnToggle.setIconResource(historyExpanded
                        ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            }
        }
    }

    /** 构建一行版本条目（最新/当前/历史共用） */
    private View buildReleaseRow(final ReleaseInfo ri, boolean isCurrent) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.item_release_version, updateView.findViewById(R.id.box_latest_version), false);
        ((TextView) row.findViewById(R.id.tv_rel_version)).setText("v" + ri.ver);

        TextView badge = row.findViewById(R.id.tv_rel_badge);
        int cmp = compareVersion(ri.ver, baseVersionName());
        if (isCurrent) {
            badge.setText("当前");
            badge.setVisibility(View.VISIBLE);
        } else if (cmp > 0) {
            badge.setText("可更新");
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }

        File cached = updateCacheFileByVer(ri.ver);
        final boolean hasCache = cached.exists() && cached.length() > 0
                && (ri.apkSize <= 0 || cached.length() == ri.apkSize);

        StringBuilder meta = new StringBuilder();
        if (!ri.date().isEmpty()) meta.append(ri.date());
        if (ri.apkSize > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(fmtSize(ri.apkSize));
        }
        if (hasCache) meta.append(" · 已下载");
        if (!isCurrent) meta.append(" · 点按查看并安装");
        ((TextView) row.findViewById(R.id.tv_rel_meta)).setText(meta.toString());

        ImageView act = row.findViewById(R.id.iv_rel_action);
        if (act != null) {
            act.setImageResource(isCurrent ? R.drawable.ic_refresh
                    : hasCache ? R.drawable.ic_save : R.drawable.ic_download);
        }
        final boolean fc = isCurrent;
        row.setOnClickListener(v -> showReleaseDetail(ri, fc, hasCache));
        return row;
    }


    /** 判断是否深色模式 */
    private static boolean isNightMode(android.content.Context c) {
        int ui = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 导出已下载的新版本安装包到公共目录（Download/HY_VQ/）。
     * 供「详情弹窗」与「新版本提示弹窗」调用。
     */
    /** 授权返回后自动重试导出的参数 */
    private File pendingExportApk = null;
    private String pendingExportVer = null;

    private void exportCachedApk(final File apk, final String ver) {
        if (apk == null || !apk.exists() || apk.length() == 0) {
            Toast.makeText(this, "安装包不存在，请先下载", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean pubVal = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                pubVal = android.os.Environment.isExternalStorageManager();
            } catch (Throwable ignored) {
            }
        }

        // 未授权 → 弹窗：去授权 / 仅导出到应用专属目录
        if (!pubVal) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(ModuleUiKit.sectionHeader(this, "需要「所有文件访问」权限"));
            TextView tv = new TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setLineSpacing(0, 1.45f);
            tv.setTextColor(ModuleUiKit.color(this,
                    com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(dp2(4), dp2(4), dp2(4), dp2(4));
            tv.setText("导出到 Download 目录需要「所有文件访问」权限。\n\n"
                    + "· 去授权 → 导出到 Download/HY_VQ/，文件管理器直接可见\n"
                    + "· 仅导出 → 导出到应用专属目录，部分机型不易访问");
            box.addView(tv);
            LinearLayout pbtns = new LinearLayout(this);
            pbtns.setOrientation(LinearLayout.HORIZONTAL);
            pbtns.setGravity(Gravity.END);
            box.addView(pbtns);
            final android.app.Dialog pd = ModuleUiKit.glassDialog(this, box);
            pbtns.addView(updateTextButton("仅导出", v -> {
                pd.dismiss();
                doExportCached(apk, ver, getExternalFilesDir(null), "HY_VQ-v" + ver + ".apk");
            }));
            pbtns.addView(updateTextButton("去授权", v -> {
                pd.dismiss();
                pendingExportApk = apk;
                pendingExportVer = ver;
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }));
            pd.show();
            return;
        }

        File base = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "HY_VQ");
        doExportCached(apk, ver, base, "HY_VQ-v" + ver + ".apk");
    }

    /** 实际执行复制（后台线程） */
    private void doExportCached(final File apk, final String ver, final File base, final String name) {
        new Thread(() -> {
            try {
                if (base != null && !base.exists() && !base.mkdirs()) {
                    throw new java.io.IOException("无法创建目录 " + base.getAbsolutePath());
                }
                File dst = new File(base, name);
                try (java.io.FileInputStream in = new java.io.FileInputStream(apk);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                final String path = dst.getAbsolutePath();
                final long size = dst.length();
                runOnUiThread(() -> {
                    Toast.makeText(this, "已导出：" + path + "（" + fmtSize(size) + "）",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                final String msg = t.getMessage();
                runOnUiThread(() ->
                        Toast.makeText(this, "导出失败：" + msg, Toast.LENGTH_LONG).show());
            }
        }, "export-apk").start();
    }

    /** 版本详情弹窗：完整更新日志 + 下载/安装/重装 */
    private void showReleaseDetail(final ReleaseInfo ri, boolean isCurrent, boolean hasCache) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "v" + ri.ver));

        // 更新日志用 Markdown 渲染（共享 MarkdownUtils，与文件管理预览一致）
        String meta = "";
        if (!ri.date().isEmpty()) meta += "发布于 " + ri.date();
        if (ri.apkSize > 0) {
            if (!meta.isEmpty()) meta += " · ";
            meta += fmtSize(ri.apkSize);
        }
        if (isCurrent) {
            if (!meta.isEmpty()) meta += " · ";
            meta += "当前已安装";
        }
        String body = ri.body == null ? "" : ri.body.trim();
        String mdSrc = (meta.isEmpty() ? "" : meta + "\n\n")
                + (body.isEmpty() ? "· 该版本无更新说明" : body);

        boolean isLight = !isNightMode(this);
        String html = com.aliya.hy_vq.util.MarkdownUtils.toDocument(mdSrc, null, isLight);
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.getSettings().setTextZoom(100);
        wv.getSettings().setBuiltInZoomControls(false);
        wv.setBackgroundColor(ModuleUiKit.color(this,
                isLight ? com.google.android.material.R.attr.colorSurface
                        : com.google.android.material.R.attr.colorOnSurface));
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        box.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2(340)));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog d = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("关闭", v -> d.dismiss()));
        btns.addView(updateTextButton(
                isCurrent ? "重新安装" : (hasCache ? "直接安装" : "下载并安装"), v -> {
                    d.dismiss();
                    File c = updateCacheFileByVer(ri.ver);
                    boolean hit = c.exists() && c.length() > 0
                            && (ri.apkSize <= 0 || c.length() == ri.apkSize);
                    if (hit) {
                        verifyAndInstall(c, null, null);
                    } else {
                        startUpdate(ri.apkUrl, ri.apkCnUrl, null, ri.apkSize, ri.ver, null);
                    }
                }));
        // 有缓存时提供导出选项（不安装，仅保存到公共目录）
        File cached = updateCacheFileByVer(ri.ver);
        if (cached.exists() && cached.length() > 0) {
            final File fc = cached;
            btns.addView(updateTextButton("导出", v -> {
                d.dismiss();
                exportCachedApk(fc, ri.ver);
            }));
        }
        d.show();
    }


    /** 同步设置页上的更新状态标签 */
    /** 同步设置页上的更新状态标签 */
    private void updateUpdateStateLabel() {
        if (settingsView == null) return;
        TextView tv = settingsView.findViewById(R.id.tv_update_state);
        if (tv == null) return;
        String curVer = baseVersionName();
        if (!updReleases.isEmpty() && compareVersion(updReleases.get(0).ver, curVer) > 0) {
            tv.setText("有新版 " + updReleases.get(0).ver);
        } else if (updCachedApk != null) {
            tv.setText("待安装");
        } else {
            tv.setText("v" + curVer);
        }
    }


    /** 主操作按钮：按当前状态选择「下载」或「直接安装」 */
    /** 主操作按钮：更新到最新版，或安装本机缓存包 */
    private void onUpdateAction() {
        if (!updReleases.isEmpty()) {
            ReleaseInfo newest = updReleases.get(0);
            if (compareVersion(newest.ver, baseVersionName()) > 0) {
                File c = updateCacheFileByVer(newest.ver);
                if (c.exists() && c.length() > 0
                        && (newest.apkSize <= 0 || c.length() == newest.apkSize)) {
                    verifyAndInstall(c, null, null);
                } else {
                    startUpdate(newest.apkUrl, newest.apkCnUrl, null,
                            newest.apkSize, newest.ver, null);
                }
                return;
            }
        }
        if (updCachedApk != null) {
            installApk(updCachedApk);
            return;
        }
        Toast.makeText(this, "暂无可安装的更新，请先检查更新", Toast.LENGTH_SHORT).show();
    }


    /** 扫描全部可用版本（GitHub Releases API），返回列表按版本号倒序 */
    /** 扫描可用版本：GitHub 优先（信息最全），失败则回退国内 123 云盘 */
    private java.util.List<ReleaseInfo> fetchReleases() {
        java.util.List<ReleaseInfo> gh = fetchReleasesFromGitHub();
        if (!gh.isEmpty()) return gh;
        return fetchReleasesFrom123();
    }

    /** GitHub Releases API（含完整更新日志与精确直链） */
    private java.util.List<ReleaseInfo> fetchReleasesFromGitHub() {
        java.util.List<ReleaseInfo> out = new java.util.ArrayList<>();
        try {
            java.net.HttpURLConnection conn = openRemote(
                    REMOTE_RELEASES, "GET", "application/vnd.github+json");
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return out;
            }
            org.json.JSONArray arr = new org.json.JSONArray(readAll(conn.getInputStream()));
            conn.disconnect();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject r = arr.getJSONObject(i);
                if (r.optBoolean("draft", false) || r.optBoolean("prerelease", false)) continue;
                ReleaseInfo ri = new ReleaseInfo();
                ri.tag = r.optString("tag_name", "");
                ri.ver = ri.tag.startsWith("v") ? ri.tag.substring(1) : ri.tag;
                if (ri.ver.isEmpty()) continue;
                ri.body = r.optString("body", "");
                ri.publishedAt = r.optString("published_at", "");
                org.json.JSONArray assets = r.optJSONArray("assets");
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        org.json.JSONObject a = assets.getJSONObject(k);
                        String nm = a.optString("name", "");
                        String url = a.optString("browser_download_url", "");
                        if (!nm.endsWith(".apk") || url.isEmpty()) continue;
                        boolean better = ri.apkUrl.isEmpty()
                                || (nm.contains(ri.ver) && !ri.apkName.contains(ri.ver));
                        if (better) {
                            ri.apkName = nm;
                            ri.apkUrl = url;
                            ri.apkSize = a.optLong("size", 0L);
                        }
                    }
                }
                if (!ri.apkUrl.isEmpty()) {
                    // ⭐ 关键：国内源文件名规则固定，**无条件推断**国内直链，
                    // 这样即使 GitHub 列表读取成功，下载时 GitHub 不可达也能自动回退国内源。
                    // （若国内源并无该文件，回退尝试会失败并被忽略，不影响主流程）
                    ri.apkCnUrl = CN_BASE + "HY_VQ-v" + ri.ver + ".apk";
                    out.add(ri);
                }
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, (a, b) -> compareVersion(b.ver, a.ver));
        return out;
    }

    /** 国内备用源：123 云盘 WebDAV 上的 versions.json（发布时同步写入） */
    private java.util.List<ReleaseInfo> fetchReleasesFrom123() {
        java.util.List<ReleaseInfo> out = new java.util.ArrayList<>();
        try {
            java.net.HttpURLConnection conn = openRemote(CN_VERSIONS, "GET");
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return out;
            }
            org.json.JSONObject root = new org.json.JSONObject(readAll(conn.getInputStream()));
            conn.disconnect();
            org.json.JSONArray arr = root.optJSONArray("versions");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                ReleaseInfo ri = new ReleaseInfo();
                ri.ver = o.optString("ver", "");
                ri.tag = "v" + ri.ver;
                if (ri.ver.isEmpty()) continue;
                ri.body = o.optString("body", "");
                ri.publishedAt = o.optString("publishedAt", "");
                ri.apkName = o.optString("apk", "");
                ri.apkSize = o.optLong("size", 0L);
                if (!ri.apkName.isEmpty()) ri.apkCnUrl = CN_BASE + ri.apkName;
                out.add(ri);
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, (a, b) -> compareVersion(b.ver, a.ver));
        return out;
    }


    /**
     * 启动流程的更新检查（受开关控制，静默进行，仅发现新版本才提示）。
     *
     * <p>启动链路：**先判登录状态** → 未登录则去 LoginActivity，登录成功后回到本页；
     * 已登录则直接进入本页。两条路径都会走到这里，<b>立即发起检查、不做延迟等待</b>
     * （此前是延后 2.5 秒，用户反馈不需要这种等待）。</p>
     *
     * <p>用 {@code handler.post} 而非直接调用，是把检查放到当前消息队列尾部，
     * 既"立即"又能让首帧先完成布局，避免白屏期间抢主线程。</p>
     */
    private void autoCheckUpdateOnLaunch() {
        // ⭐ 只在「进入软件」时检测一次（进程级守卫）。此前无守卫 → 每次 onCreate 都检测，
        // 而 Activity 会因旋转 / 被系统回收 / 从安装器返回而重建 → 下载途中突然又弹一次
        // 「发现新版本」，用户会误以为在重复下载。静态标志随进程存在，重建不再触发。
        if (launchUpdateChecked) return;
        launchUpdateChecked = true;
        if (prefs == null || !prefs.getBoolean(PREF_AUTO_CHECK_UPDATE, true)) return;
        android.util.Log.i("HyVqUpdate", "启动检查：立即发起（无需等待）");
        handler.post(this::silentCheckUpdate);
    }

    /** 后台静默检查：不显示「检查中」，发现新版本才提示 */
    /** 后台静默扫描版本列表：不显示「扫描中」，发现新版本才弹窗 */
    private void silentCheckUpdate() {
        new Thread(() -> {
            final java.util.List<ReleaseInfo> list = fetchReleases();
            if (list.isEmpty()) return;
            runOnUiThread(() -> {
                if (updState == 1) return;   // 用户手动扫描中，不覆盖
                if (updateFlowBusy) return;  // ⭐ 正在下载/校验/安装：绝不在此期间弹更新提示
                updReleases = list;
                updState = 2;
                if (updateView != null) renderUpdateView();
                else updateUpdateStateLabel();
                ReleaseInfo newest = list.get(0);
                if (compareVersion(newest.ver, baseVersionName()) > 0
                        && prefs.getBoolean(PREF_NOTIFY_NEW_VERSION, true)) {
                    showAutoUpdatePrompt(newest);
                }
            });
        }).start();
    }


    /** 自动检查发现新版本时弹出提示，由用户决定是否更新 */
    /** 自动扫描发现新版本时弹出提示，由用户决定是否更新 */
    private void showAutoUpdatePrompt(final ReleaseInfo ri) {
        if (ri == null || ri.ver.isEmpty() || autoPromptedVer.equals(ri.ver)) return;
        autoPromptedVer = ri.ver;

        final File cached = updateCacheFileByVer(ri.ver);
        final boolean hasCache = cached.exists() && cached.length() > 0
                && (ri.apkSize <= 0 || cached.length() == ri.apkSize);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "🎉 发现新版本"));

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.4f);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tv.setPadding(pad, pad, pad, pad);

        StringBuilder sb = new StringBuilder();
        sb.append("v").append(ri.ver);
        if (ri.apkSize > 0) sb.append("    ").append(fmtSize(ri.apkSize));
        if (!ri.date().isEmpty()) sb.append("    ").append(ri.date());
        sb.append(System.lineSeparator()).append(System.lineSeparator());

        final int MAX_LINES = 6;
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String ln : ri.body.split(System.lineSeparator())) {
            if (!ln.trim().isEmpty()) lines.add(ln);
        }
        if (lines.isEmpty()) {
            sb.append("· 细节优化与问题修复");
        } else {
            for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
                sb.append(lines.get(i)).append(System.lineSeparator());
            }
            if (lines.size() > MAX_LINES) {
                sb.append("… 共 ").append(lines.size()).append(" 项，点「详情」查看全部");
            }
        }
        if (hasCache) {
            sb.append(System.lineSeparator()).append(System.lineSeparator())
                    .append("📦 安装包已下载（").append(fmtSize(cached.length())).append("），可直接安装");
        }
        tv.setText(sb.toString());
        box.addView(tv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("稍后", v -> dialog.dismiss()));
        btns.addView(updateTextButton("详情", v -> {
            dialog.dismiss();
            switchToUpdate();
        }));
        btns.addView(updateTextButton(hasCache ? "立即安装" : "立即更新", v -> {
            dialog.dismiss();
            if (hasCache) verifyAndInstall(cached, null, null);
            else startUpdate(ri.apkUrl, ri.apkCnUrl, null, ri.apkSize, ri.ver, null);
        }));
        // 有缓存时提供导出选项
        if (hasCache) {
            btns.addView(updateTextButton("导出", v -> {
                dialog.dismiss();
                exportCachedApk(cached, ri.ver);
            }));
        }
        dialog.show();
    }


    /** 检查更新：拉取远程清单后刷新页面（结果全部体现在页面内容里） */
    /** 检查更新：扫描全部可用版本后刷新页面（结果全部体现在页面内容里） */
    private void checkRemoteUpdate() {
        updState = 1;
        updError = "";
        renderUpdateView();
        new Thread(() -> {
            final java.util.List<ReleaseInfo> list = fetchReleases();
            runOnUiThread(() -> {
                if (list.isEmpty()) {
                    updState = 4;
                    updError = "无法获取版本列表，请检查网络后重试";
                } else {
                    updReleases = list;
                    updState = 2;
                    autoPromptedVer = "";   // 手动扫描后允许重新提示
                }
                renderUpdateView();
            });
        }).start();
    }


    // ── 更新包缓存（原生安装器不会自动删除缓存 APK，故自行管理）──

    // ==================== 存储管理 ====================

    private long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return dir.length();
        long total = 0L;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) total += dirSize(f);
        }
        return total;
    }

    private File trashDir() {
        return new File(Environment.getExternalStorageDirectory(), ".HyVqTrash");
    }

    private void refreshStorageLabel() {
        if (settingsView == null) return;
        TextView tv = settingsView.findViewById(R.id.tv_storage_state);
        if (tv == null) return;
        try {
            android.os.StatFs sf = new android.os.StatFs(
                    Environment.getExternalStorageDirectory().getAbsolutePath());
            tv.setText(fmtSize(sf.getAvailableBytes()) + " 可用");
        } catch (Throwable t) {
            tv.setText("");
        }
    }

    /** 存储管理：用量概览 + 缓存清理 + 回收站清空 */
    // ══════════════════════════════════════════════════════════════
    //  致谢（三板块：引用的 / 借鉴的 / 不再使用但曾参考的）
    // ══════════════════════════════════════════════════════════════

    /** 板块一：本项目直接引用的开源库与免费服务 */
    private static final String[] CREDITS_USED = {
            "AndroidX —— 基础支持库（AppCompat / RecyclerView / FileProvider 等）",
            "Material Components for Android —— Material 3 组件与主题体系",
            "呜哇小站 emoji.wuwa.games —— 免费提供鸣潮表情包 API（本应用已主动限流）",
    };

    /** 板块二：借鉴其设计思路与交互的项目（未直接使用其代码） */
    private static final String[] CREDITS_INSPIRED = {
            "Material Files —— 目录滚动位置记忆、双窗格交互的设计思路",
            "MT 管理器 —— 双窗格文件管理交互与长按菜单的参考",
            "ZhuFiler —— 主题叠加与目录缓存实现的参考",
            "Amaze File Manager —— 底部导航栏（文件/分类/回收站/网络）的风格参考",
            "Fossify File Manager —— 同类实现与交互细节参考",
            "Ghost Commander —— 网络能力（FTP 服务端）的方向参考",
            "Blurry / BlurView —— 浮窗与侧边栏背景模糊的实现原理参考",
    };

    /** 板块三：历史参考 —— 代码现已不再使用，但开发过程中曾受益 */
    private static final String[] CREDITS_HISTORY = {
            "Jetpack Media3 / ExoPlayer —— 曾评估引入媒体播放，因本项目需离线构建而改用原生实现",
            "pnpm —— 早期构建与依赖管理工具（非应用内组件）",
    };

    /**
     * 致谢弹窗：三个板块分别列出「引用的」「借鉴的」「历史参考的」。
     * <p>拆成独立弹窗而非内嵌折叠，是因为条目较多、内嵌会把关于页撑得过长。</p>
     */
    private void showCreditsDialog() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp2(4), dp2(4), dp2(4), dp2(4));
        sv.addView(box);
        // 限制高度，避免条目多时弹窗超出屏幕
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (getResources()
                .getDisplayMetrics().heightPixels * 0.62)));

        box.addView(ModuleUiKit.sectionHeader(this, "致谢"));

        TextView intro = new TextView(this);
        intro.setText("本项目的实现得益于以下开源项目与免费服务。"
                + "按使用方式分为三类，一并致谢。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        intro.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        intro.setLineSpacing(dp2(2), 1.25f);
        intro.setPadding(dp2(4), dp2(2), dp2(4), dp2(6));
        box.addView(intro);

        addCreditsSection(box, "① 引用的", "直接使用了其库或服务",
                CREDITS_USED, com.google.android.material.R.attr.colorPrimary);
        addCreditsSection(box, "② 借鉴的", "未用其代码，参考了设计与交互",
                CREDITS_INSPIRED, com.google.android.material.R.attr.colorSecondary);
        addCreditsSection(box, "③ 历史参考", "代码现已不再使用，但开发中曾受益",
                CREDITS_HISTORY, com.google.android.material.R.attr.colorTertiary);

        TextView tail = new TextView(this);
        tail.setText("感谢上述所有开源作者与免费服务的提供者。\n"
                + "本项目以 GPL-3.0 发布，相关代码与资源版权归各自原作者所有。");
        tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tail.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tail.setLineSpacing(dp2(2), 1.25f);
        tail.setPadding(dp2(4), dp2(12), dp2(4), dp2(4));
        box.addView(tail);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(this, sv);
        // 弹窗底部补一个关闭按钮
        box.addView(updateTextButtonRow("关闭", v -> ModuleUiKit.dismissWithAnim(dialog)));
        dialog.show();
    }

    /** 致谢弹窗里的一个板块：标题 + 副标题 + 条目列表 */
    private void addCreditsSection(LinearLayout parent, String title, String subtitle,
                                   String[] items, int colorAttr) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setPadding(dp2(12), dp2(10), dp2(12), dp2(10));
        sec.setBackground(ModuleUiKit.rounded(this, 12,
                ModuleUiKit.color(this,
                        com.google.android.material.R.attr.colorSurfaceContainerLow),
                ModuleUiKit.color(this,
                        com.google.android.material.R.attr.colorOutlineVariant)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp2(10);
        sec.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(ModuleUiKit.color(this, colorAttr));
        sec.addView(t);

        TextView st = new TextView(this);
        st.setText(subtitle + "（" + items.length + " 项）");
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        st.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        st.setPadding(0, dp2(2), 0, dp2(6));
        sec.addView(st);

        for (String it : items) {
            TextView row = new TextView(this);
            row.setText("· " + it);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setTextColor(ModuleUiKit.color(this,
                    com.google.android.material.R.attr.colorOnSurface));
            row.setLineSpacing(dp2(3), 1.2f);
            row.setPadding(0, dp2(2), 0, dp2(2));
            sec.addView(row);
        }
        parent.addView(sec);
    }

    /** 生成一行右对齐按钮容器（含单个按钮） */
    private LinearLayout updateTextButtonRow(String text, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp2(14);
        row.setLayoutParams(lp);
        row.addView(updateTextButton(text, onClick));
        return row;
    }

    /** 刷新设置页「显示密度」右侧的状态文字 */
    private void refreshDpiLabel() {
        if (settingsView == null) return;
        TextView tv = settingsView.findViewById(R.id.tv_dpi_state);
        if (tv == null) return;
        int dpi = com.aliya.hy_vq.util.DpiUtils.get(this);
        int sys = com.aliya.hy_vq.util.DpiUtils.systemDpi(this);
        tv.setText(dpi == com.aliya.hy_vq.util.DpiUtils.DEFAULT
                ? "跟随系统（" + sys + "）"
                : dpi + " dpi");
    }

    /**
     * 显示密度设置弹窗（应用级 DPI 覆盖，范围 100–600）。
     * <p>只影响本应用的 dp→px 换算，不改系统设置、不需要任何权限；
     * 保存后调用 {@link #recreate()} 让新密度立即生效。</p>
     */
    private void showDpiDialog() {
        final int sysDpi = com.aliya.hy_vq.util.DpiUtils.systemDpi(this);
        final int saved = com.aliya.hy_vq.util.DpiUtils.get(this);
        final int cur = saved == com.aliya.hy_vq.util.DpiUtils.DEFAULT ? sysDpi : saved;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "显示密度（DPI）"));

        TextView tip = new TextView(this);
        tip.setText("调整本应用的界面缩放，数值越大界面元素越大。仅影响 HY_VQ，"
                + "不改动系统设置，卸载或重置即还原。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tip.setLineSpacing(dp2(2), 1.25f);
        tip.setPadding(dp2(4), dp2(4), dp2(4), dp2(8));
        box.addView(tip);

        // ── 当前值大字 ──
        final TextView tvVal = new TextView(this);
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tvVal.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvVal.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorPrimary));
        tvVal.setGravity(Gravity.CENTER);
        tvVal.setText(String.valueOf(cur));
        box.addView(tvVal);

        TextView tvSys = new TextView(this);
        tvSys.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSys.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSys.setGravity(Gravity.CENTER);
        tvSys.setText("设备默认 " + sysDpi + "　范围 "
                + com.aliya.hy_vq.util.DpiUtils.MIN + "–" + com.aliya.hy_vq.util.DpiUtils.MAX);
        tvSys.setPadding(0, 0, 0, dp2(8));
        box.addView(tvSys);

        // ── 滑块 ──
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        final int span = com.aliya.hy_vq.util.DpiUtils.MAX - com.aliya.hy_vq.util.DpiUtils.MIN;
        sb.setMax(span);
        sb.setProgress(Math.max(0, Math.min(span, cur - com.aliya.hy_vq.util.DpiUtils.MIN)));
        box.addView(sb);

        // ── 数值输入框 ──
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(cur));
        et.setGravity(Gravity.CENTER);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp2(6);
        box.addView(et, elp);

        TextView tvHint = new TextView(this);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvHint.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvHint.setGravity(Gravity.CENTER);
        tvHint.setText("取 " + com.aliya.hy_vq.util.DpiUtils.MIN + "–"
                + com.aliya.hy_vq.util.DpiUtils.MAX + " 之间的整数，越界会被自动夹取");
        box.addView(tvHint);

        // ── 预设按钮 ──
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prlp.topMargin = dp2(10);
        box.addView(presetRow, prlp);
        for (final int preset : com.aliya.hy_vq.util.DpiUtils.PRESETS) {
            TextView b = updateTextButton(String.valueOf(preset), v -> {
                sb.setProgress(Math.max(0, Math.min(span,
                        preset - com.aliya.hy_vq.util.DpiUtils.MIN)));
                et.setText(String.valueOf(preset));
                et.setSelection(et.getText().length());
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp2(6);
            presetRow.addView(b, lp);
        }

        // 滑块 ↔ 输入框 双向同步
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                int v = com.aliya.hy_vq.util.DpiUtils.MIN + progress;
                tvVal.setText(String.valueOf(v));
                if (fromUser) {
                    et.setText(String.valueOf(v));
                    et.setSelection(et.getText().length());
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) { }
        });

        // ── 按钮 ──
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp2(12);
        box.addView(btns, blp);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("跟随系统", v -> {
            com.aliya.hy_vq.util.DpiUtils.reset(this);
            ModuleUiKit.dismissWithAnim(dialog);
            applyDpiAndRestart();
        }));
        btns.addView(updateTextButton("取消", v -> ModuleUiKit.dismissWithAnim(dialog)));
        btns.addView(updateTextButton("应用", v -> {
            int v2;
            try {
                v2 = Integer.parseInt(et.getText().toString().trim());
            } catch (Throwable t) {
                v2 = com.aliya.hy_vq.util.DpiUtils.MIN + sb.getProgress();
            }
            com.aliya.hy_vq.util.DpiUtils.set(this, v2);
            ModuleUiKit.dismissWithAnim(dialog);
            applyDpiAndRestart();
        }));
        dialog.show();
    }

    /** 密度已写入 prefs，重启 Activity 让 attachBaseContext 重新生效 */
    private void applyDpiAndRestart() {
        refreshDpiLabel();
        Toast.makeText(this, "已应用，正在重新加载界面…", Toast.LENGTH_SHORT).show();
        // 稍作延迟，让 Toast 与弹窗退场动画走完再重建
        handler.postDelayed(() -> {
            try {
                recreate();
            } catch (Throwable t) {
                Toast.makeText(this, "请手动重启应用以生效", Toast.LENGTH_LONG).show();
            }
        }, 260);
    }

    private void showStorageManagerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "📦 存储管理"));

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.4f);
        tv.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        int pad = dp2(4);
        tv.setPadding(pad, pad, pad, pad);

        long total = 0, free = 0;
        try {
            android.os.StatFs sf = new android.os.StatFs(
                    Environment.getExternalStorageDirectory().getAbsolutePath());
            total = sf.getTotalBytes();
            free = sf.getAvailableBytes();
        } catch (Throwable ignored) {
        }
        long used = Math.max(0, total - free);
        int pct = total > 0 ? (int) (used * 100 / total) : 0;
        long cacheBytes = dirSize(updateCacheDir());
        File trash = trashDir();
        long trashBytes = dirSize(trash);
        File[] tf = trash.listFiles();
        int trashCount = tf == null ? 0 : tf.length;

        StringBuilder sb = new StringBuilder();
        sb.append("内部存储").append(System.lineSeparator());
        sb.append("· 总容量　").append(fmtSize(total)).append(System.lineSeparator());
        sb.append("· 已使用　").append(fmtSize(used)).append("（").append(pct).append("%）").append(System.lineSeparator());
        sb.append("· 可　用　").append(fmtSize(free)).append(System.lineSeparator());
        sb.append(System.lineSeparator()).append("可清理项").append(System.lineSeparator());
        sb.append("· 更新包缓存　").append(fmtSize(cacheBytes)).append(System.lineSeparator());
        sb.append("· 回收站　　").append(trashCount).append(" 项 · ").append(fmtSize(trashBytes));
        tv.setText(sb.toString());
        box.addView(tv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        box.addView(btns);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(this, box);
        btns.addView(updateTextButton("清理更新缓存", v -> {
            File[] fs = updateCacheDir().listFiles();
            int n = 0;
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().startsWith("hyvq_update") && f.delete()) n++;
                }
            }
            Toast.makeText(this, "已清理 " + n + " 个更新包缓存", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            refreshStorageLabel();
        }));
        btns.addView(updateTextButton("清空回收站", v -> {
            File[] fs = trash.listFiles();
            int n = fs == null ? 0 : fs.length;
            if (fs != null) {
                for (File f : fs) deleteRecursive(f);   // 既有的 void 版，递归删除
            }
            Toast.makeText(this, "已清空回收站（" + n + " 项）", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            refreshStorageLabel();
        }));
        btns.addView(updateTextButton("关闭", v -> dialog.dismiss()));
        dialog.show();
    }

    // ==================== 通知管理 ====================

    private static final String PREF_NOTIFY_NEW_VERSION = "notify_new_version";
    private static final String PREF_NOTIFY_DOWNLOAD_DONE = "notify_download_done";


    /** 通知管理：更新相关提醒的开关 */
    private void addNotifySwitch(LinearLayout parent, final String key, boolean def,
                                 String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp2(6);
        row.setPadding(pad, dp2(10), pad, dp2(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t1.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorOnSurface));
        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t2.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        row.addView(sw);
        parent.addView(row);
    }

    /** 更新页可见时同步刷新（通知开关会改变其内容） */
    private void refreshUpdateViewIfVisible() {
        if (updateView != null && updateView.getParent() != null) renderUpdateView();
    }

    private File updateCacheDir() {
        return getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
    }

    /** 指定版本的缓存安装包路径（按版本名命名，便于复用与甄别） */
    private File updateCacheFileByVer(String ver) {
        return new File(updateCacheDir(), "hyvq_update_v" + ver + ".apk");
    }

    /** 清理缓存安装包：keep 为 null 时全部清除，否则只保留 keep */
    private void cleanUpdateCache(File keep) {
        File[] fs = updateCacheDir().listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            // 覆盖新旧两种命名：新 hyvq_update_v<版本>.apk / 旧 hyvq_update.apk
            boolean isPkg = (n.startsWith("hyvq_update") && n.endsWith(".apk"))
                    || n.endsWith(".apk.part");
            if (isPkg && (keep == null || !f.getAbsolutePath().equals(keep.getAbsolutePath()))) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 扫描缓存中比当前版本更新的安装包 —— 不依赖网络即可发现「已下载待安装」 */
    private File[] findCachedNewerApks(int currentVersionCode) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] fs = updateCacheDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("hyvq_update_") || !n.endsWith(".apk") || f.length() <= 0) continue;
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi != null && pi.versionCode > currentVersionCode) out.add(f);
            }
        }
        return out.toArray(new File[0]);
    }

    private String cachedApkLabel(File apk) {
        android.content.pm.PackageInfo pi =
                getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return pi == null ? apk.getName() : "v" + pi.versionName + " (code " + pi.versionCode + ")";
    }

    /** 人类可读的传输速度（B/s → KB/s、MB/s） */
    private static String humanSpeed(double bps) {
        if (bps <= 0) return "—";
        if (bps < 1024) return String.format(java.util.Locale.US, "%.0f B/s", bps);
        if (bps < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024);
        return String.format(java.util.Locale.US, "%.2f MB/s", bps / 1048576);
    }

    /** 人类可读的剩余时长 */
    private static String humanDuration(long sec) {
        if (sec <= 0) return "即将完成";
        if (sec < 60) return sec + " 秒";
        long m = sec / 60, ss = sec % 60;
        if (m < 60) return m + " 分 " + ss + " 秒";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    private String fmtSize(long b) {
        if (b >= 1048576L) return String.format(java.util.Locale.CHINA, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return String.format(java.util.Locale.CHINA, "%.0f KB", b / 1024.0);
        return b + " B";
    }

    /** 更新入口：缓存命中则直接安装，否则带进度下载 */
    private void startUpdate(final String apkUrl, final String cnUrl, final String expectMd5,
                             final long expectSize, final String verName,
                             final android.app.Dialog dialog) {
        final File target = updateCacheFileByVer(verName);
        boolean hit = target.exists() && target.length() > 0
                && (expectSize <= 0 || target.length() == expectSize);
        if (hit) {
            verifyAndInstall(target, expectMd5, dialog);
        } else {
            downloadWithProgress(apkUrl, cnUrl, expectMd5, expectSize, target, dialog);
        }
    }

    /** 校验已有缓存包后安装（MD5 不符即清除，避免反复安装坏包） */
    private void verifyAndInstall(final File apk, final String expectMd5, final android.app.Dialog dialog) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        Toast.makeText(this, "安装包已在缓存中，正在校验…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try {
                if (expectMd5 != null && !expectMd5.isEmpty()) {
                    String actual = md5Of(apk);
                    if (!expectMd5.equalsIgnoreCase(actual)) {
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                        throw new Exception("缓存安装包已损坏（MD5 不符），已清除，请重新下载");
                    }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            runOnUiThread(() -> {
                if (ferr != null) {
                    Toast.makeText(this, ferr, Toast.LENGTH_LONG).show();
                } else {
                    cleanUpdateCache(apk);
                    renderUpdateView();
                    installApk(apk);
                }
            });
        }).start();
    }

    /** 带进度的下载：进度条实时刷新，完成后校验 MD5 再拉起安装器 */
    private void downloadWithProgress(final String apkUrl, final String cnUrl, final String expectMd5,
                                      final long expectSize, final File target,
                                      final android.app.Dialog dialog) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        cleanUpdateCache(null);   // 下载前清掉旧版本残留
        updateFlowBusy = true;    // ⭐ 进入更新流程：期间任何自动检测都不弹窗

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "⬇ 正在下载更新"));

        final int pad = dp2(4);

        final TextView tvPct = new TextView(this);
        tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvPct.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvPct.setTextColor(ModuleUiKit.color(this, com.google.android.material.R.attr.colorPrimary));
        tvPct.setPadding(pad, pad, pad, 0);
        tvPct.setText("准备中…");
        box.addView(tvPct);

        final android.widget.ProgressBar pb = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp2(8));
        plp.topMargin = dp2(6);
        box.addView(pb, plp);

        // ── 详情区：下载源 / 速度 / 剩余时间 ──
        final TextView tvSource = new TextView(this);
        tvSource.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSource.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSource.setPadding(pad, dp2(8), pad, 0);
        tvSource.setText("下载源：正在选择…");
        box.addView(tvSource);

        final TextView tvSpeed = new TextView(this);
        tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSpeed.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSpeed.setPadding(pad, dp2(3), pad, 0);
        tvSpeed.setText("下载速度：—");
        box.addView(tvSpeed);

        final TextView tvSize = new TextView(this);
        tvSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSize.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvSize.setPadding(pad, dp2(3), pad, 0);
        tvSize.setText("已下载：—");
        box.addView(tvSize);

        final TextView tvEta = new TextView(this);
        tvEta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvEta.setTextColor(ModuleUiKit.color(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvEta.setPadding(pad, dp2(3), pad, 0);
        tvEta.setText("剩余时间：—");
        box.addView(tvEta);

        // ── 取消按钮（大文件下载时很有用）──
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        LinearLayout cancelRow = new LinearLayout(this);
        cancelRow.setOrientation(LinearLayout.HORIZONTAL);
        cancelRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp2(12);
        TextView btnCancel = updateTextButton("取消下载", v -> cancelled.set(true));
        cancelRow.addView(btnCancel);
        box.addView(cancelRow, crlp);

        // 下载进度弹窗：下载中不允许点击外部关闭（否则下载中途被误关）；
        // 弹窗关闭即代表更新流程结束 → 清掉「进行中」标记（成功/失败/取消各路径都覆盖）
        final android.app.Dialog pd = ModuleUiKit.glassDialog(this, box, false);
        pd.setCancelable(false);
        pd.setOnDismissListener(d -> updateFlowBusy = false);
        pd.show();

        new Thread(() -> {
            String err = null;
            final File tmp = new File(target.getParentFile(), target.getName() + ".part");
            try {
                // ⭐ 主源尝试必须包在 try 里：GitHub 不可达时 openRemote 会**抛异常**
                // （而非返回非 200），若不加保护将直接跳到外层 catch，**回退逻辑永远执行不到**。
                // ⭐ 三级回退：GitHub 直链 → 国内加速镜像（公开）→ 123云盘（需凭据）
                java.net.HttpURLConnection conn = null;
                int code = -1;
                String mainErr = null;
                StringBuilder tried = new StringBuilder();

                // ── 第 1 级：GitHub 直链 ──
                try {
                    conn = openRemote(apkUrl, "GET");
                    code = conn.getResponseCode();
                } catch (Exception e) {
                    mainErr = e.getMessage();
                }
                if (code == 200) {
                    runOnUiThread(() -> tvSource.setText("下载源：GitHub 直链"));
                } else {
                    tried.append("主源 HTTP ").append(code)
                         .append(mainErr != null ? "(" + mainErr + ")" : "");
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Throwable ignored) { }
                    }

                    // ── 第 2 级：国内加速镜像（公开通道，不需要凭据） ──
                    for (String mir : GH_MIRRORS) {
                        String m = mirrorUrl(mir, apkUrl);
                        if (m == null) break;
                        final String host = mir.replace("https://", "").replace("/", "");
                        runOnUiThread(() -> {
                    tvPct.setText("直链不可用，尝试镜像…");
                    tvSource.setText("下载源：镜像 " + host);
                });
                        try {
                            conn = openRemote(m, "GET");
                            code = conn.getResponseCode();
                        } catch (Exception e) {
                            code = -1;
                            conn = null;
                        }
                        if (code == 200) break;
                        tried.append(" · ").append(host).append(" HTTP ").append(code);
                        if (conn != null) {
                            try { conn.disconnect(); } catch (Throwable ignored) { }
                        }
                    }

                    // ── 第 3 级：123云盘（需凭据） ──
                    if (code != 200 && cnUrl != null && !cnUrl.isEmpty()) {
                        runOnUiThread(() -> {
                            tvPct.setText("镜像均不可用，切换国内备用源…");
                            tvSource.setText("下载源：123云盘（国内备用源）");
                        });
                        conn = openRemote(cnUrl, "GET");
                        code = conn.getResponseCode();
                        if (code != 200) tried.append(" · 国内源 HTTP ").append(code);
                    }
                }
                if (code != 200) {
                    // 网盘 401 时给出可操作的提示（而非只甩一个 HTTP 码）
                    if (code == 401) {
                        throw new Exception("国内源认证失败（HTTP 401）。"
                                + "请在「软件更新」页从 GitHub 手动下载，或稍后重试。\n明细：" + tried);
                    }
                    throw new Exception("下载失败 " + tried);
                }
                long total = expectSize > 0 ? expectSize : conn.getContentLength();
                long done = 0;
                int lastPct = -1;
                long t0 = System.currentTimeMillis();
                long lastSampleTime = t0, lastSampleDone = 0;
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled.get()) {
                            throw new Exception("已取消下载");
                        }
                        fos.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        // 每 400ms 刷新一次，避免过于频繁地更新 UI
                        if (now - lastSampleTime >= 400) {
                            long dt = now - lastSampleTime;
                            long db = done - lastSampleDone;
                            double bps = dt > 0 ? (db * 1000.0 / dt) : 0;
                            lastSampleTime = now;
                            lastSampleDone = done;
                            final int fp = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                            final long fd = done, ft = total;
                            final double fbps = bps;
                            runOnUiThread(() -> {
                                if (fp >= 0) {
                                    pb.setProgress(fp);
                                    tvPct.setText(fp + "%");
                                } else {
                                    tvPct.setText("下载中…");
                                }
                                tvSize.setText("已下载：" + fmtSize(fd)
                                        + (ft > 0 ? " / " + fmtSize(ft) : ""));
                                tvSpeed.setText("下载速度：" + humanSpeed(fbps)
                                        + "　（平均 " + humanSpeed(
                                        fd * 1000.0 / Math.max(1, now - t0)) + "）");
                                if (fbps > 1 && ft > fd) {
                                    long sec = (long) ((ft - fd) / fbps);
                                    tvEta.setText("剩余时间：" + humanDuration(sec));
                                } else {
                                    tvEta.setText("剩余时间：估算中…");
                                }
                            });
                        }
                    }
                }
                conn.disconnect();
                if (tmp.length() <= 0) throw new Exception("下载内容为空");
                if (expectMd5 != null && !expectMd5.isEmpty()) {
                    String actual = md5Of(tmp);
                    if (!expectMd5.equalsIgnoreCase(actual)) {
                        throw new Exception("MD5 校验不通过，安装包可能被篡改或下载不完整");
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) throw new Exception("无法写入缓存目录");
            } catch (Exception e) {
                err = e.getMessage();
                //noinspection ResultOfMethodCallIgnored
                if (tmp.exists()) tmp.delete();
            }
            final String ferr = err;
            final File ftarget = target;
            runOnUiThread(() -> {
                if (pd.isShowing()) pd.dismiss();
                if (ferr != null) {
                    Toast.makeText(this, "下载失败：" + ferr, Toast.LENGTH_LONG).show();
                } else {
                    cleanUpdateCache(ftarget);   // 只保留刚下载的这版
                    renderUpdateView();
                    if (prefs.getBoolean(PREF_NOTIFY_DOWNLOAD_DONE, true)) {
                        Toast.makeText(this, "更新包已下载并校验完成", Toast.LENGTH_SHORT).show();
                    }
                    installApk(ftarget);
                }
            });
        }).start();
    }

    /** 计算文件 MD5（更新包完整性校验） */
    private String md5Of(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 拉起系统安装器（FileProvider 授权）；未授予「安装未知应用」时先引导授权 */
    private void installApk(File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "请先允许「安装未知应用」后重试", Toast.LENGTH_LONG).show();
                Intent s = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(s);
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "拉起安装器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private String readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    /** 玻璃弹窗内的文字按钮（与 ModuleUiKit.glassDialog 观感统一：主色文字 + 圆角浅底） */
    private TextView dialogTextButton(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(resolveAttr(com.google.android.material.R.attr.colorPrimary));
        tv.setPadding(dp2(16), dp2(8), dp2(16), dp2(8));
        tv.setClickable(true);
        tv.setBackground(ModuleUiKit.rounded(this, dp2(10),
                resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerHighest), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp2(8));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private int dp2(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ==================== 权限中心 ====================
    // 动态列出应用持有的「全部」权限（含系统自动授予、在系统设置里看不到的项目），
    // 分层展示 + 点击查看用途说明。实现见 module/PermissionCenterView。

    private void switchToPermissions() {
        permissionsView = new PermissionCenterView(this, this::refreshPermissionsView);
        switchContent(permissionsView, PAGE_PERMISSIONS);
        setSubpageToolbar("权限管理");
    }

    /** 重建权限中心（从系统设置授权完返回后，状态能立即刷新） */
    private void refreshPermissionsView() {
        if (permissionsView == null || permissionsView.getParent() == null) return;
        permissionsView = new PermissionCenterView(this, this::refreshPermissionsView);
        switchContent(permissionsView, PAGE_PERMISSIONS);
    }

    private void showDefaultPageDialog() {
        String current = prefs.getString("default_page", "home");
        // ⭐ v2.9.3 弹窗统一：此前这里是**手搓 Dialog**（bg_dialog_add_friend 背景 + 系统 Button
        // + 自设窗口宽度/模糊），与全项目 glassDialog 的玻璃面板观感不一致，且没有点外关闭。
        // 现改为与其它弹窗完全同源：玻璃面板 + 1dp 描边 + 背景模糊 + 点阴影处关闭。
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(this, "默认启动页"));

        String[] items = {"首页"};
        String[] values = {"home"};
        int[] icons = {R.drawable.ic_home};
        int selectedIdx = 0;

        // 横向双卡片，16:9 比例（宽=高*16/9）
        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setGravity(Gravity.CENTER);

        int cardW = (int) (screenW() * 0.30f); // 每张卡片占屏幕 30%
        int cardH = (int) (cardW * 9f / 16f);   // 16:9 反比 → 宽屏卡片

        final int[] picked = {selectedIdx};
        final com.google.android.material.card.MaterialCardView[] cardRefs =
                new com.google.android.material.card.MaterialCardView[2];

        for (int i = 0; i < items.length; i++) {
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            card.setRadius(16);
            card.setCardElevation(2f);
            card.setCardBackgroundColor(resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerLow));
            // 模糊背景上卡片轮廓：默认 1dp 中性描边，选中 2dp 主题色描边
            card.setStrokeWidth(i == selectedIdx ? 2 : 1);
            card.setStrokeColor(i == selectedIdx
                    ? resolveAttr(com.google.android.material.R.attr.colorPrimary)
                    : resolveAttr(com.google.android.material.R.attr.colorOutlineVariant));
            card.setClickable(true);
            card.setFocusable(true);

            // 内嵌垂直布局：图标 + 名称
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(24, 20, 24, 20);

            ImageView iv = new ImageView(this);
            iv.setImageResource(icons[i]);
            iv.setColorFilter(resolveAttr(com.google.android.material.R.attr.colorPrimary));
            LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(56, 56);
            iv.setLayoutParams(ivp);
            inner.addView(iv);

            TextView tv = new TextView(this);
            tv.setText(items[i]);
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            tv.setTextColor(resolveAttr(com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(0, 12, 0, 0);
            tv.setGravity(Gravity.CENTER);
            inner.addView(tv);

            card.addView(inner);
            cardRefs[i] = card;

            final int idx = i;
            card.setOnClickListener(v2 -> {
                picked[0] = idx;
                for (int j = 0; j < cardRefs.length; j++) {
                    cardRefs[j].setStrokeWidth(j == idx ? 2 : 1);
                    cardRefs[j].setStrokeColor(j == idx
                            ? resolveAttr(com.google.android.material.R.attr.colorPrimary)
                            : resolveAttr(com.google.android.material.R.attr.colorOutlineVariant));
                    cardRefs[j].setCardBackgroundColor(
                            j == idx ? resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer)
                                     : resolveAttr(com.google.android.material.R.attr.colorSurfaceContainerLow));
                }
            });

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(cardW, cardH);
            cp.leftMargin = i == 0 ? 0 : 16;
            card.setLayoutParams(cp);
            cardsRow.addView(card);
        }
        box.addView(cardsRow);

        // 按钮行：改用与其它玻璃弹窗同款的文字按钮（不再用系统 Button）
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp2(16);
        box.addView(btns, btnsLp);

        final Dialog dialog = ModuleUiKit.glassDialog(this, box, true);
        btns.addView(dialogTextButton("取消", v2 -> dialog.dismiss()));
        btns.addView(dialogTextButton("确定", v2 -> {
            prefs.edit().putString("default_page", values[picked[0]]).apply();
            Toast.makeText(this, "已设置默认启动页为：" + items[picked[0]], Toast.LENGTH_SHORT).show();
            // 同步设置页显示的默认页文案
            if (settingsView != null) {
                TextView tvDefault = settingsView.findViewById(R.id.tv_default_page);
                if (tvDefault != null) tvDefault.setText(items[picked[0]]);
            }
            dialog.dismiss();
        }));

        dialog.setOnDismissListener(d -> activeDialogs.remove(dialog));
        activeDialogs.add(dialog);
        dialog.show();
    }

    private void switchToAccount() {
        if (accountView == null) {
            accountView = LayoutInflater.from(this).inflate(R.layout.fragment_account, contentFrame, false);
            setupAccountView();
        }
        switchContent(accountView, PAGE_ACCOUNT);
        setSubpageToolbar("账号管理");
    }

    private void setupAccountView() {
        // 账号签名管理
        accountView.findViewById(R.id.item_signature).setOnClickListener(v -> showSignatureDialog());
    }

    // 屏幕宽度缓存（用于动画，避免 getWidth() 首次返回 0）
    private int cachedScreenW = -1;
    private int screenW() {
        if (cachedScreenW <= 0) cachedScreenW = getResources().getDisplayMetrics().widthPixels;
        return cachedScreenW;
    }
    // SiliconUI-inspired: exponential decay easing (fast start, gentle settle)
    private static final PathInterpolator SI_EXP_EASE = new PathInterpolator(0.12f, 0f, 0f, 1f);
    // SiliconUI-inspired: spring overshoot for list/panel transitions
    private static final OvershootInterpolator SI_SPRING = new OvershootInterpolator(1.2f);

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!goBack()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
    }
}