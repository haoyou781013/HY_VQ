package com.aliya.hy_vq.module.impl;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.aliya.hy_vq.github.GhApi;
import com.aliya.hy_vq.github.GhDeviceFlow;
import com.aliya.hy_vq.module.HyVqModule;
import com.aliya.hy_vq.module.ModuleServices;
import com.aliya.hy_vq.module.GhUi;
import com.aliya.hy_vq.module.ModuleUiKit;
import com.aliya.hy_vq.util.MarkdownWebView;
import com.google.android.material.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 模块 —— API 客户端形态（非网页版）。
 *
 * <p><b>设计依据（2026-10-12 实测，真实大陆网络）：</b>
 * <pre>
 *   github.com                成功率 1/5   ← 网页版主站被间歇掐断
 *   api.github.com            成功率 5/5   ← 本模块只走这里
 *   codeload.github.com       成功率 5/5
 *   raw.githubusercontent.com 成功率 4/5
 * </pre>
 * 结论：网页路线在国内注定不稳定；<b>API 路线结构性稳定且完全不需要代理</b>。
 * 这也是 GitHub 官方 App 在国内稳定的原因——它是 API 客户端，不是浏览器。
 *
 * <p><b>登录：</b>OAuth Device Flow 或粘贴 PAT，拿到 token 后走 Bearer 鉴权，
 * <b>不依赖 Cookie</b>（详见 {@link GhDeviceFlow} 的说明：跨 App 共享 Cookie
 * 在 Android 上无 API 可行；公共代理会把 Cookie 绑到代理域名导致登录态失效）。
 */
public class GithubModule extends HyVqModule {

    private Context ctx;
    /** Activity 上下文：取主题属性（attr 颜色）必须用它，Application 上下文取不到 */
    private Context act;
    private GhDeviceFlow auth;
    private GhApi api;

    private LinearLayout root;
    private TextView statusLine;
    private LinearLayout listBox;
    private ProgressBar busy;
    /** 当前仓库上下文（目录浏览时携带分支） */
    private String curOwner, curRepo, curRef;
    /** 仓库页当前分段：0=代码 1=Issues 2=PR */
    private int repoTab = 0;
    /** 主线程 Handler（getMainLooper() 返回 Looper，不能直接 post） */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    public GithubModule() {
        this.id = "github";
        this.name = "GitHub";
        this.version = "1.0.0";
        this.icon = "code";
        this.showInDrawer = true;
        this.isBuiltIn = true;
        this.declaredApis = "api.github.com（只读浏览；登录后读私有仓库）";
    }

    @Override
    public void onAttach(Context context, ModuleServices services) {
        this.ctx = context.getApplicationContext();
        this.auth = new GhDeviceFlow(ctx);
        this.api = new GhApi(ctx, auth.token());
    }

    @Override
    public String getSummary() {
        return auth != null && auth.isLoggedIn()
                ? "已登录：" + auth.loginName()
                : "未登录（游客可浏览公开仓库，登录限流 60→5000 次/小时）";
    }

    // ── 主视图 ────────────────────────────────────────

    @Override
    public View createMainView(android.view.LayoutInflater inflater, ViewGroup parent) {
        // ⚠️ 必须用 inflater.getContext()（Activity 上下文），不能用 onAttach 传的
        // Application 上下文 —— 取主题属性（ModuleUiKit.color 靠 attr）必须 Activity theme。
        final Context c = inflater.getContext();
        this.act = c;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        // ⚠️ 必须显式给 MATCH_PARENT：content_frame 高度是 0dp + layout_weight，
        // 不设 LayoutParams 时 LinearLayout 默认 WRAP_CONTENT 会算出 0 高 → 整页空白。
        // （FileManagerModule 也是这么做的，2026-10-12 踩过这个坑）
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(ModuleUiKit.color(c, R.attr.colorSurface));

        // 说明：为什么是 API 客户端而不是网页
        root.addView(ModuleUiKit.sectionHeader(c, "☁ GitHub · API 客户端"));
        TextView note = tv(c, 11,
                "走 api.github.com（实测国内 5/5 通畅），不用网页版 github.com（实测 1/5）。"
                        + "全程无需代理，登录态用 token 而非 Cookie。");
        note.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurfaceVariant));
        int pd = dp(c, 12);
        note.setPadding(pd, 0, pd, dp(c, 8));
        root.addView(note);

        statusLine = tv(c, ModuleUiKit.TYPE_BODY, "");
        statusLine.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurface));
        statusLine.setPadding(pd, 0, pd, dp(c, 6));
        root.addView(statusLine);

        busy = new ProgressBar(c);
        busy.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = pd;
        root.addView(busy, blp);

        listBox = new LinearLayout(c);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(pd, dp(c, 6), pd, dp(c, 16));

        // 外层套滚动容器：仓库文件树可能很长，需要能滚动
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(listBox, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 首次进入：游客态也能用，只是功能受限
        root.post(() -> {
            android.util.Log.i("HY_VQ", "GithubModule.post: showGuestMenu");
            if (auth.isLoggedIn()) refreshAccount();
            else showGuestMenu();
        });
        return root;
    }

    // ── 未登录：游客可浏览 + 登录入口 ──────────────────

    private void showGuestMenu() {
        clear();
        statusLine.setText("未登录 · 游客限流 60 次/小时（登录后 5000 次/小时）");

        listBox.addView(card("🔍", "搜索公开仓库", "无需登录即可用", v -> showSearch()));
        listBox.addView(card("👤", "登录 GitHub", "OAuth 设备码 / 粘贴 Token", v -> showLoginSheet()));
    }

    // ── 已登录：账号 + 我的仓库 ────────────────────────

    private void refreshAccount() {
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final JSONObject me = api.me();
            final JSONArray repos = me != null ? api.myRepos(1, 30) : null;
            final String rate = api.rateInfo();
            ui.post(() -> {
                busy.setVisibility(View.GONE);
                clear();
                if (me == null) {
                    statusLine.setText("token 已失效，请重新登录");
                    showGuestMenu();
                    return;
                }
                String login = me.optString("login", "?");
                statusLine.setText("已登录 " + login
                        + (rate == null ? "" : "　·　API 配额 " + rate));
                listBox.addView(card("📦", "我的仓库", "最近推送的 30 个", v -> showMyRepos(repos)));
                listBox.addView(card("🔍", "搜索仓库", "公开仓库", v -> showSearch()));
                listBox.addView(card("🚪", "退出登录", "清除本地 token", v -> confirmLogout()));
            });
        }).start();
    }

    private void showMyRepos(final JSONArray repos) {
        clear();
        statusLine.setText("我的仓库");
        listBox.addView(ModuleUiKit.cardRow(act, 0, "← 返回", "",
                v -> refreshAccount()));
        if (repos == null) {
            showError("读取仓库列表失败。", () -> refreshAccount());
            return;
        }
        if (repos.length() == 0) {
            // Blankslate 空态（替代裸 TextView）
            showBlank("📭", "还没有仓库", "登录后这里会列出你的仓库；也可以直接搜索别人的公开仓库。",
                    "🔍 搜索仓库", v -> showSearch());
            return;
        }
        for (int i = 0; i < repos.length(); i++) {
            final JSONObject r = repos.optJSONObject(i);
            if (r == null) continue;
            addRepoRow(r);
        }
    }

    /** 仓库列表项（Primer 三行式 + 语言色点 + 相对时间） */
    private void addRepoRow(JSONObject r) {
        final String full = r.optString("full_name", "");
        if (full.isEmpty()) return;
        listBox.addView(GhUi.repoItem(act,
                full,
                r.optString("description", ""),
                r.optString("language", ""),
                GhUi.relativeTime(r.optString("updated_at", "")),
                r.optInt("stargazers_count", 0),
                v -> showRepo(full)));
    }

    private void showSearch() {
        clear();
        statusLine.setText("搜索仓库");
        listBox.addView(ModuleUiKit.cardRow(act, 0, "← 返回", "",
                v -> {
                    if (auth.isLoggedIn()) refreshAccount();
                    else showGuestMenu();
                }));

        final EditText input = new EditText(act);
        input.setHint("关键词，如 HY_VQ / genshin / android");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, ModuleUiKit.TYPE_BODY_LARGE);
        int pd = dp(act, 12);
        input.setPadding(pd, dp(act, 10), pd, dp(act, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, dp(act, 6), 0, dp(act, 8));
        listBox.addView(input, ilp);

        listBox.addView(card("🔎", "搜索", "走 api.github.com/search", v -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) {
                ModuleUiKit.toast(act, "请输入关键词");
                return;
            }
            busy.setVisibility(View.VISIBLE);
            new Thread(() -> {
                final JSONArray res = api.searchRepos(q, 1);
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    showSearchResults(res, q);
                });
            }).start();
        }));
    }

    private void showSearchResults(final JSONArray repos, final String query) {
        clear();
        statusLine.setText("搜索：" + query);
        listBox.addView(ModuleUiKit.cardRow(act, 0, "← 返回搜索", "",
                v -> showSearch()));
        if (repos == null) {
            showError("搜索失败。未登录时限流仅 60 次/小时，登录后可提升到 5000 次/小时。",
                    () -> showSearch());
            return;
        }
        if (repos.length() == 0) {
            showBlank("🔍", "没有找到匹配的仓库", "换个关键词试试，或去掉 language: 等限定条件。",
                    "← 重新搜索", v -> showSearch());
            return;
        }
        for (int i = 0; i < repos.length(); i++) {
            JSONObject r = repos.optJSONObject(i);
            if (r == null) continue;
            addRepoRow(r);
        }
    }

    // ── 仓库浏览：文件树 + README ──────────────────────

    private void showRepo(final String fullName) {
        final String[] parts = fullName.split("/");
        if (parts.length < 2) return;
        final String owner = parts[0], repo = parts[1];

        clear();
        busy.setVisibility(View.VISIBLE);
        statusLine.setText(fullName);
        new Thread(() -> {
            final JSONObject meta = api.repo(owner, repo);
            final JSONArray files = api.contents(owner, repo, "", null);
            final String readme = readReadme(owner, repo);
            ui.post(() -> {
                busy.setVisibility(View.GONE);
                clear();
                statusLine.setText(fullName);
                if (meta != null) {
                    String desc = meta.optString("description", "");
                    if (!desc.isEmpty()) listBox.addView(tvLine(desc));
                    listBox.addView(tvLine("★ " + meta.optInt("stargazers_count", 0)
                            + "　Fork " + meta.optInt("forks_count", 0)
                            + "　默认分支 " + meta.optString("default_branch", "main")));
                }
                if (readme != null && !readme.isEmpty()) {
                    listBox.addView(ModuleUiKit.sectionHeader(act, "README"));
                    WebReadmeView rv = new WebReadmeView(act, readme);
                    listBox.addView(rv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 400)));
                }
                listBox.addView(ModuleUiKit.sectionHeader(act, "文件"));
                listBox.addView(ModuleUiKit.cardRow(act, 0, "← 返回", "", v -> {
                    if (auth.isLoggedIn()) refreshAccount();
                    else showGuestMenu();
                }));
                if (files == null) {
                    listBox.addView(tvLine("读取文件列表失败"));
                    return;
                }
                for (int i = 0; i < files.length(); i++) {
                    final JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String type = f.optString("type", "file");
                    String path = f.optString("path", "");
                    String name = f.optString("name", "");
                    if ("README.md".equals(name) || "README".equals(name)) continue;
                    String icon = "dir".equals(type) ? "📂" : "📄";
                    listBox.addView(card(icon, name, "", v -> {
                        if ("dir".equals(type)) showDir(owner, repo, path);
                        else showFile(owner, repo, path);
                    }));
                }
            });
        }).start();
    }

    private String readReadme(String owner, String repo) {
        String[] candidates = {"README.md", "readme.md", "README.rst", "README"};
        for (String c : candidates) {
            String t = api.fileText(owner, repo, c, null);
            if (t != null && !t.trim().isEmpty()) return t;
        }
        return null;
    }

    private void showDir(String owner, String repo, String path) {
        clear();
        statusLine.setText(owner + "/" + repo + "/" + path);
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final JSONArray files = api.contents(owner, repo, path, null);
            ui.post(() -> {
                busy.setVisibility(View.GONE);
                clear();
                statusLine.setText(path);
                listBox.addView(ModuleUiKit.cardRow(act, 0, "← 上级", "", v -> showRepo(owner + "/" + repo)));
                if (files == null) {
                    listBox.addView(tvLine("读取失败"));
                    return;
                }
                for (int i = 0; i < files.length(); i++) {
                    final JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String type = f.optString("type", "file");
                    String p = f.optString("path", "");
                    String name = f.optString("name", "");
                    listBox.addView(card("dir".equals(type) ? "📂" : "📄", name, "", v -> {
                        if ("dir".equals(type)) showDir(owner, repo, p);
                        else showFile(owner, repo, p);
                    }));
                }
            });
        }).start();
    }

    private void showFile(String owner, String repo, String path) {
        clear();
        statusLine.setText(path);
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final String text = api.fileText(owner, repo, path, null);
            ui.post(() -> {
                busy.setVisibility(View.GONE);
                clear();
                statusLine.setText(path);
                listBox.addView(ModuleUiKit.cardRow(act, 0, "← 返回", "",
                        v -> showDir(owner, repo, parentOf(path))));
                if (text == null) {
                    listBox.addView(tvLine("读取失败或文件过大（>1MB 需走 raw 接口）"));
                    return;
                }
                TextView body = tv(act, 12, text);
                body.setTypeface(android.graphics.Typeface.MONOSPACE);
                body.setTextIsSelectable(true);
                body.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
                body.setBackground(ModuleUiKit.rounded(act, 12,
                        ModuleUiKit.color(act, R.attr.colorSurfaceContainerLow), 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(act, 8);
                listBox.addView(body, lp);
            });
        }).start();
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "" : path.substring(0, i);
    }

    // ── 登录 ──────────────────────────────────────────

    private void showLoginSheet() {
        clear();
        statusLine.setText("登录 GitHub");

        listBox.addView(ModuleUiKit.cardRow(act, 0, "← 返回", "", v -> showGuestMenu()));

        if (GhDeviceFlow.deviceFlowConfigured()) {
            listBox.addView(card("🔑", "设备码登录（推荐）",
                    "在浏览器输入一次性码，无需输密码", v -> startDeviceFlow()));
        } else {
            TextView warn = tvLine("设备码登录尚未配置 OAuth App（需填 Client ID）");
            warn.setTextColor(ModuleUiKit.color(act, R.attr.colorError));
            listBox.addView(warn);
        }

        listBox.addView(card("📋", "粘贴 Token",
                "Personal Access Token，存在本机不外传", v -> showPatInput()));

        final EditText input = new EditText(act);
        input.setHint("粘贴 PAT（ghp_…）");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, ModuleUiKit.TYPE_BODY_LARGE);
        int pd = dp(act, 12);
        input.setPadding(pd, dp(act, 10), pd, dp(cp(act), 10));
        input.setVisibility(View.GONE);
        listBox.addView(input);

        listBox.addView(card("✅", "确认登录", "", v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) {
                ModuleUiKit.toast(act, "请先粘贴 Token");
                return;
            }
            busy.setVisibility(View.VISIBLE);
            new Thread(() -> {
                final String login = new GhDeviceFlow(act).verifyAndGetLogin(t);
                ui.post(() -> {
                    busy.setVisibility(View.GONE);
                    if (login.isEmpty()) {
                        ModuleUiKit.toast(act, "Token 无效，请检查");
                    } else {
                        auth.savePat(t, login);
                        api = new GhApi(act, t);
                        ModuleUiKit.toast(act, "已登录 " + login);
                        refreshAccount();
                    }
                });
            }).start();
        }));
        // 点"粘贴 Token"时显示输入框
        listBox.getChildAt(2).setOnClickListener(v2 -> {
            input.setVisibility(View.VISIBLE);
            input.requestFocus();
        });
    }

    private void showPatInput() {
        showLoginSheet();
    }

    private void startDeviceFlow() {
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final GhDeviceFlow.Start st = auth.start("repo");
            ui.post(() -> {
                busy.setVisibility(View.GONE);
                if (!st.ok) {
                    ModuleUiKit.toast(act, st.message);
                    return;
                }
                showDeviceCodePrompt(st);
            });
        }).start();
    }

    private void showDeviceCodePrompt(final GhDeviceFlow.Start st) {
        final Context c = ctx;
        TextView title = tv(c, ModuleUiKit.TYPE_HEADLINE, "输入设备码");
        title.setGravity(Gravity.CENTER);
        TextView code = tv(c, 34, st.userCode);
        code.setGravity(Gravity.CENTER);
        code.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        code.setTextColor(ModuleUiKit.color(c, R.attr.colorPrimary));
        code.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        code.setBackground(ModuleUiKit.rounded(c, 16,
                ModuleUiKit.color(c, R.attr.colorPrimaryContainer), 0));
        TextView tip = tv(c, 12, "打开 " + st.verifyUrl + " 输入上面的码。"
                + "在外部浏览器（如 Edge）完成即可，本 App 会自动等待。");
        tip.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurfaceVariant));
        tip.setGravity(Gravity.CENTER);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(c, 16);
        box.setPadding(pd, pd, pd, pd);
        box.addView(title, lpWrap());
        box.addView(code, lpWrap(dp(c, 10)));
        box.addView(tip, lpWrap(dp(c, 14)));

        final TextView state = tv(c, 12, "等待授权…");
        state.setGravity(Gravity.CENTER);
        state.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurfaceVariant));
        box.addView(state, lpWrap(dp(c, 12)));

        final androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(c)
                        .setView(box)
                        .setNegativeButton("取消", null)
                        .create();
        dlg.setOnDismissListener(v -> pollHandler.removeCallbacksAndMessages(null));
        dlg.show();

        // 按 GitHub 建议的间隔轮询
        final long intervalMs = Math.max(5, st.interval) * 1000L;
        schedulePoll(st.deviceCode, intervalMs, state, dlg);
    }

    private final android.os.Handler pollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private void schedulePoll(final String deviceCode, final long intervalMs,
                              final TextView state, final androidx.appcompat.app.AlertDialog dlg) {
        pollHandler.postDelayed(() -> {
            if (!dlg.isShowing()) return;
            new Thread(() -> {
                final GhDeviceFlow.Poll p = auth.poll(deviceCode);
                ui.post(() -> {
                    if (!dlg.isShowing()) return;
                    if (p.done) {
                        String login = auth.verifyAndGetLogin(p.token);
                        auth.saveToken(p.token, login);
                        api = new GhApi(act, p.token);
                        ModuleUiKit.toast(act, "已登录 " + (login.isEmpty() ? "" : login));
                        dlg.dismiss();
                        refreshAccount();
                        return;
                    }
                    if (p.expired || p.denied) {
                        ModuleUiKit.toast(act, p.message);
                        dlg.dismiss();
                        return;
                    }
                    if (p.pending) {
                        state.setText("等待授权…");
                        schedulePoll(deviceCode, intervalMs, state, dlg);
                    } else if (p.slowDown) {
                        state.setText("放慢轮询中…");
                        schedulePoll(deviceCode, intervalMs + 3000, state, dlg);
                    } else {
                        state.setText("网络异常，重试中…");
                        schedulePoll(deviceCode, intervalMs * 2, state, dlg);
                    }
                });
            }).start();
        }, intervalMs);
    }

    private void confirmLogout() {
        new androidx.appcompat.app.AlertDialog.Builder(act)
                .setTitle("退出登录")
                .setMessage("将清除本机保存的 token，之后回到游客状态（60 次/小时限流）。")
                .setPositiveButton("退出", (d, w) -> {
                    auth.logout();
                    api = new GhApi(act, "");
                    showGuestMenu();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ── 小组件 ────────────────────────────────────────

    // ══════════════ 三态渲染（Primer 规范）══════════════

    /** 加载态：骨架屏（Primer SkeletonText）—— 比转圈减少感知延迟，且不跳动高度 */
    private void showSkeleton(int rows) {
        clear();
        listBox.addView(GhUi.skeletonList(act, rows));
    }

    /** 空状态：Blankslate（图标 + 标题 + 说明 + 行动按钮） */
    private void showBlank(String icon, String heading, String desc,
                           String action, View.OnClickListener onAction) {
        clear();
        listBox.addView(GhUi.blankslate(act, icon, heading, desc, action, onAction));
    }

    /** 错误态：InlineMessage 内联 + 重试（不要 Toast 一闪而过） */
    private void showError(String message, final Runnable retry) {
        clear();
        listBox.addView(GhUi.inlineMessage(act, null, message, true, "重试",
                retry == null ? null : v -> retry.run()));
        if (retry != null) {
            TextView detail = tvLine("若反复失败，可能是：未登录（限流 60 次/小时）、"
                    + "网络不可达、或该内容需要更高权限。");
            listBox.addView(detail);
        }
    }

    /** 行内提示（非错误） */
    private void showInfo(String message) {
        clear();
        listBox.addView(GhUi.inlineMessage(act, null, message, false, null, null));
    }

    private void clear() {
        if (listBox != null) listBox.removeAllViews();
    }

    private LinearLayout card(String icon, String title, String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pd = dp(act, 12);
        row.setPadding(pd, dp(act, GhUi.SPACE_MD), pd, dp(act, GhUi.SPACE_MD));
        // 触摸目标 ≥48dp（Primer 官方 Mobile Checklist 硬指标）
        row.setMinimumHeight(dp(act, GhUi.TOUCH_MIN));
        row.setBackground(ModuleUiKit.rippleBg(act, ModuleUiKit.rounded(act, 12,
                GhUi.surfaceLow(act), 0)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(act, GhUi.SPACE_SM);
        row.setLayoutParams(lp);

        TextView ic = tv(act, 18, icon);
        row.addView(ic, new LinearLayout.LayoutParams(dp(act, 34), dp(act, 34)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(act, ModuleUiKit.TYPE_BODY_LARGE, title);
        t.setTextColor(ModuleUiKit.color(act, R.attr.colorOnSurface));
        col.addView(t);
        if (desc != null && !desc.isEmpty()) {
            TextView d = tv(act, ModuleUiKit.TYPE_CAPTION, desc);
            d.setTextColor(ModuleUiKit.color(act, R.attr.colorOnSurfaceVariant));
            col.addView(d);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = tv(act, 14, "›");
        arrow.setTextColor(ModuleUiKit.color(act, R.attr.colorOnSurfaceVariant));
        row.addView(arrow);

        row.setOnClickListener(onClick);
        return row;
    }

    private TextView tv(Context c, float sp, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurface));
        return v;
    }

    private TextView tvLine(String s) {
        TextView v = tv(act, 12, s);
        v.setTextColor(ModuleUiKit.color(act, R.attr.colorOnSurfaceVariant));
        v.setPadding(dp(act, 4), dp(act, 6), dp(act, 4), dp(act, 6));
        return v;
    }

    private LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWrap(int topMargin) {
        LinearLayout.LayoutParams lp = lpWrap();
        lp.topMargin = topMargin;
        return lp;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static Context cp(Context c) {
        return c;
    }

    /** README 渲染容器（复用既有的 MarkdownWebView，JavaScript 已禁用） */
    private static class WebReadmeView extends LinearLayout {
        WebReadmeView(Context c, String md) {
            super(c);
            setOrientation(VERTICAL);
            View w = MarkdownWebView.create(c,
                    ModuleUiKit.color(c, R.attr.colorSurface), null);
            addView(w, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            w.post(() -> {
                try {
                    w.getClass().getMethod("loadDataWithBaseURL", String.class, String.class,
                            String.class, String.class)
                            .invoke(w, null, toHtml(md), "text/html", "UTF-8");
                } catch (Exception ignored) {
                }
            });
        }

        private static String toHtml(String md) {
            String body = com.aliya.hy_vq.util.MarkdownUtils.toHtml(md);
            return "<html><head><meta name=\"viewport\" content=\"width=device-width,"
                    + "initial-scale=1\"><style>body{margin:12px;font-size:14px;"
                    + "line-height:1.6;word-wrap:break-word;overflow-wrap:break-word}"
                    + "img{max-width:100%}pre{background:#f5f5f5;padding:8px;border-radius:6px;"
                    + "overflow-x:auto}code{background:#f0f0f0;padding:2px 4px;border-radius:4px}"
                    + "a{color:#0969da}</style></head><body>" + body + "</body></html>";
        }
    }
}