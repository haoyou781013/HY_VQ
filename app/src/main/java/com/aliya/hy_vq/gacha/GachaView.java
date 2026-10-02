package com.aliya.hy_vq.gacha;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aliya.hy_vq.module.ModuleUiKit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 祈愿分析页（侧边栏入口）。
 *
 * <p>交互：粘贴一条或多条抽卡链接 → 解析 authkey → 拉取各卡池记录 →
 * <b>按 uid 融合进本地存档</b> → 展示统计。</p>
 *
 * <p>因为服务端只保留 6 个月记录，本页鼓励「多次导入」：
 * 同一个 uid 无论导入多少次、来自多少条链接，都会去重合并成一份完整历史。</p>
 */
public class GachaView extends LinearLayout {

    private final Context ctx;
    private LinearLayout poolBox;      // 卡池统计区
    private LinearLayout uidBox;       // 已存档 uid 列表
    private EditText input;            // 链接输入框
    private TextView status;           // 状态提示
    private View resultCard;           // 结果卡（有数据才显示）
    private String currentUid = "";

    public GachaView(Context context) {
        super(context);
        this.ctx = context;
        setOrientation(VERTICAL);
        buildUi();
    }

    // ══════════════════════════════════════════════
    //  UI 构建
    // ══════════════════════════════════════════════

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private int color(int attr) { return ModuleUiKit.color(ctx, attr); }

    private void buildUi() {
        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, dp(32));
        sv.addView(root);

        // ── 标题 ──
        // 标题行：标题 + BETA 徽章
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("祈愿分析");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        titleRow.addView(title);

        TextView beta = new TextView(ctx);
        beta.setText("BETA");
        beta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        beta.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        beta.setTextColor(color(com.google.android.material.R.attr.colorOnTertiaryContainer));
        beta.setBackground(ModuleUiKit.rounded(ctx, 6,
                color(com.google.android.material.R.attr.colorTertiaryContainer), 0));
        beta.setPadding(dp(7), dp(2), dp(7), dp(2));
        LinearLayout.LayoutParams betaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        betaLp.leftMargin = dp(8);
        titleRow.addView(beta, betaLp);
        root.addView(titleRow);

        // 未完善提示条
        TextView betaNote = new TextView(ctx);
        betaNote.setText("⚠️ 本功能仍在完善中：卡池分类与保底规则可能随官方调整而变化，"
                + "统计结果仅供参考。如发现异常请在仓库反馈。");
        betaNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        betaNote.setTextColor(color(com.google.android.material.R.attr.colorOnTertiaryContainer));
        betaNote.setBackground(ModuleUiKit.rounded(ctx, 8,
                color(com.google.android.material.R.attr.colorTertiaryContainer), 0));
        betaNote.setLineSpacing(dp(2), 1.25f);
        betaNote.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        root.addView(betaNote, nlp);

        TextView sub = new TextView(ctx);
        sub.setText("粘贴抽卡链接即可统计。同一 uid 多次导入会自动融合去重；"
                + "服务端仅保留 6 个月记录，建议定期导入以免丢失。");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        sub.setLineSpacing(dp(2), 1.25f);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // ── 已存档帐号 ──
        uidBox = new LinearLayout(ctx);
        uidBox.setOrientation(VERTICAL);
        root.addView(uidBox);

        // ── 导入卡 ──
        LinearLayout importCard = card();
        TextView lb = new TextView(ctx);
        lb.setText("粘贴抽卡链接（支持多条，一行一条）");
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lb.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        importCard.addView(lb);

        input = new EditText(ctx);
        input.setHint("https://webstatic.mihoyo.com/hk4e/event/…&authkey=…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        input.setHintTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        importCard.addView(input, ilp);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        importCard.addView(btnRow, blp);

        TextView btnAuto = btn("自动获取", color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorTertiary));
        btnAuto.setOnClickListener(v -> doAutoFetch());
        btnRow.addView(btnAuto);

        TextView btnMys = btn("米游社登录", color(com.google.android.material.R.attr.colorOnSecondary),
                color(com.google.android.material.R.attr.colorSecondary));
        btnMys.setOnClickListener(v -> showMiyousheLogin());
        btnRow.addView(btnMys);

        TextView btnHow = btn("如何获取链接", color(com.google.android.material.R.attr.colorPrimary),
                color(com.google.android.material.R.attr.colorSurfaceContainerHighest));
        btnHow.setOnClickListener(v -> showHowToDialog());
        btnRow.addView(btnHow);

        TextView btnImport = btn("解析并导入", color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        btnImport.setOnClickListener(v -> doImport());
        btnRow.addView(btnImport);

        TextView btnPaste = btn("从剪贴板", color(com.google.android.material.R.attr.colorPrimary),
                color(com.google.android.material.R.attr.colorSurfaceContainerHighest));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.rightMargin = dp(8);
        btnPaste.setOnClickListener(v -> pasteFromClipboard());
        btnRow.addView(btnPaste, 0, plp);

        root.addView(importCard);

        // ── 状态 ──
        status = new TextView(ctx);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        status.setPadding(dp(4), dp(10), dp(4), dp(2));
        status.setVisibility(GONE);
        root.addView(status);

        // ── 结果区 ──
        resultCard = card();
        ((LinearLayout) resultCard).removeAllViews();
        poolBox = new LinearLayout(ctx);
        poolBox.setOrientation(VERTICAL);
        ((LinearLayout) resultCard).addView(poolBox);
        resultCard.setVisibility(GONE);
        root.addView(resultCard);

        addView(sv);
        refreshUidList();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(VERTICAL);
        int pad = dp(14);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(ModuleUiKit.rounded(ctx, 14,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView btn(String text, int fg, int bg) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(fg);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setBackground(ModuleUiKit.rounded(ctx, 10, bg, 0));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private TextView kv(String k, String v, int valueColor) {
        TextView t = new TextView(ctx);
        t.setText(k + "  " + v);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(valueColor);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    // ══════════════════════════════════════════════
    //  已存档帐号列表
    // ══════════════════════════════════════════════

    private void refreshUidList() {
        uidBox.removeAllViews();
        List<String> uids = GachaStore.listUids(ctx);
        if (uids.isEmpty()) return;

        LinearLayout c = card();
        TextView h = new TextView(ctx);
        h.setText("已存档帐号（点按查看统计）");
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        c.addView(h);

        for (final String uid : uids) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackground(ModuleUiKit.rounded(ctx, 10,
                    color(com.google.android.material.R.attr.colorSurfaceContainerHighest), 0));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(8);

            TextView tv = new TextView(ctx);
            tv.setText("UID " + uid);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            row.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            int n = GachaStore.load(ctx, uid).size();
            TextView cnt = new TextView(ctx);
            cnt.setText(n + " 条");
            cnt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cnt.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            row.addView(cnt);

            TextView del = new TextView(ctx);
            del.setText("删除");
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            del.setTextColor(color(com.google.android.material.R.attr.colorError));
            del.setPadding(dp(12), dp(4), dp(4), dp(4));
            del.setOnClickListener(v -> confirmDeleteUid(uid));
            row.addView(del);

            row.setOnClickListener(v -> showStats(uid));
            c.addView(row, rlp);
        }
        uidBox.addView(c);
    }

    private void confirmDeleteUid(final String uid) {
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("删除存档")
                .setMessage("确定删除 UID " + uid + " 的全部祈愿记录？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    GachaStore.remove(ctx, uid);
                    if (uid.equals(currentUid)) {
                        resultCard.setVisibility(GONE);
                        currentUid = "";
                    }
                    refreshUidList();
                    Toast.makeText(ctx, "已删除 UID " + uid, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ══════════════════════════════════════════════
    //  导入流程
    // ══════════════════════════════════════════════

    private void pasteFromClipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                if (t != null && t.length() > 0) {
                    String cur = input.getText().toString().trim();
                    input.setText(cur.isEmpty() ? t.toString() : cur + "\n" + t);
                    setStatus("已从剪贴板追加链接");
                    return;
                }
            }
            Toast.makeText(ctx, "剪贴板为空", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "读取剪贴板失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void setStatus(String s) {
        status.setText(s);
        status.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
    }

    private void doImport() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(ctx, "请先粘贴抽卡链接", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<GachaApi.AuthInfo> infos = GachaApi.parseAuthInfos(text);
        if (infos.isEmpty()) {
            setStatus("❌ 未在文本中找到 authkey，请确认粘贴的是完整抽卡链接");
            return;
        }
        setStatus("已解析出 " + infos.size() + " 条凭证，开始拉取…");

        new Thread(() -> {
            int totalAdded = 0, totalSkipped = 0, failCount = 0;
            Set<String> touchedUids = new LinkedHashSet<>();
            String lastError = null;

            for (int i = 0; i < infos.size(); i++) {
                final GachaApi.AuthInfo auth = infos.get(i);
                final int idx = i + 1;
                // 先用该凭证试探性地拉一次，拿到 uid 后再按 uid 去重拉取
                GachaApi.FetchResult probe = GachaApi.fetchPool(auth, "301", null,
                        (fetched, running) -> runOnUi(() ->
                                setStatus("凭证 " + idx + "/" + infos.size()
                                        + " 拉取中… 已获取 " + fetched + " 条")));
                if (probe.error != null) {
                    failCount++;
                    lastError = probe.error;
                    continue;
                }
                String uid = probe.uid;
                if (uid == null || uid.isEmpty()) {
                    failCount++;
                    lastError = "未能获取 UID";
                    continue;
                }
                touchedUids.add(uid);
                Set<String> existing = GachaStore.idSet(ctx, uid);
                // 把试探得到的记录先并入候选（它们可能不在 existing 中）
                List<GachaRecord> acc = new ArrayList<>(probe.records);
                existing.addAll(idOf(probe.records));

                for (String type : GachaApi.ALL_TYPES) {
                    if ("301".equals(type)) continue;   // 已拉过
                    final String t = type;
                    GachaApi.FetchResult one = GachaApi.fetchPool(auth, t, existing,
                            (fetched, running) -> runOnUi(() ->
                                    setStatus("账号 " + uid + " · " + GachaRecord.poolName(t)
                                            + " 已获取 " + fetched + " 条")));
                    if (one.error == null) {
                        acc.addAll(one.records);
                        existing.addAll(idOf(one.records));
                    } else if (one.authkeyExpired) {
                        lastError = "authkey 已失效，请重新从游戏获取链接";
                    }
                }

                GachaStore.MergeResult mr = GachaStore.merge(ctx, uid, acc);
                totalAdded += mr.added;
                totalSkipped += mr.skipped;
            }

            final int fAdded = totalAdded, fSkipped = totalSkipped, fFail = failCount;
            final String fErr = lastError;
            final List<String> uids = new ArrayList<>(touchedUids);
            runOnUi(() -> {
                boolean anyOk = fAdded > 0 || fSkipped > 0;
                StringBuilder sb = new StringBuilder();
                sb.append(anyOk ? "✅ " : "❌ ");
                sb.append("导入完成：新增 ").append(fAdded).append(" 条");
                if (fSkipped > 0) sb.append("，跳过重复 ").append(fSkipped).append(" 条");
                if (fFail > 0) sb.append("，失败 ").append(fFail).append(" 条凭证");
                if (fErr != null) sb.append("\n").append(anyOk ? "⚠️ " : "❌ ").append(fErr);
                setStatus(sb.toString());
                input.setText("");
                refreshUidList();
                if (!uids.isEmpty()) showStats(uids.get(0));
            });
        }, "HyVqGachaImport").start();
    }

    private static List<String> idOf(List<GachaRecord> list) {
        List<String> out = new ArrayList<>();
        for (GachaRecord r : list) if (r != null && !r.id.isEmpty()) out.add(r.id);
        return out;
    }

    private void runOnUi(Runnable r) {
        if (ctx instanceof android.app.Activity) {
            ((android.app.Activity) ctx).runOnUiThread(r);
        } else {
            // 非 Activity 上下文时退化为 View 自带的 post（会在 attach 后执行）
            post(r);
        }
    }

    // ══════════════════════════════════════════════
    //  统计展示
    // ══════════════════════════════════════════════

    /** 打开页面时若有存档，自动展示最近一个 uid 的统计 */
    public void showLatestIfAny() {
        List<String> uids = GachaStore.listUids(ctx);
        if (!uids.isEmpty()) showStats(uids.get(0));
    }

    // ══════════════════════════════════════════════
    //  自动获取链接（Shizuku → 读系统日志）
    // ══════════════════════════════════════════════

    private GachaLinkFetcher.State lastShizukuState = null;

    /**
     * Shizuku 授权回调。
     * <p>刻意**懒创建**而非字段初始化：字段初始化发生在 View 构造期，
     * 一旦 Shizuku 相关类加载出现问题会直接导致整页崩溃；
     * 懒加载可把风险限制在「点击自动获取」这一刻，且能就地捕获反馈。</p>
     */
    private Object permListenerObj = null;

    private rikka.shizuku.Shizuku.OnRequestPermissionResultListener permListener() {
        if (permListenerObj == null) {
            permListenerObj = (rikka.shizuku.Shizuku.OnRequestPermissionResultListener)
                    (requestCode, grantResult) -> {
                        try {
                            if (requestCode != GachaLinkFetcher.REQ_SHIZUKU_PERMISSION) return;
                            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                setStatus("已获得 Shizuku 授权，正在读取系统日志…");
                                runScan();
                            } else {
                                setStatus("❌ Shizuku 授权被拒绝。可再次点击「自动获取」重新授权。");
                            }
                        } catch (Throwable t) {
                            setStatus("❌ 授权回调异常：" + t);
                        }
                    };
        }
        return (rikka.shizuku.Shizuku.OnRequestPermissionResultListener) permListenerObj;
    }

    /** 「自动获取」入口：按 Shizuku 状态分流 */
    /**
     * 「自动获取」入口。
     *
     * <p>⚠️ 全流程必须在<b>子线程</b>执行：Shizuku 的状态查询与权限申请都是
     * Binder 调用，服务端无响应时会长时间阻塞；放在主线程会直接触发 ANR
     * （表现为界面卡死然后被系统杀掉）。</p>
     */
    private void doAutoFetch() {
        setStatus("正在检查 Shizuku 状态…");
        new Thread(() -> {
            try {
                final GachaLinkFetcher.State st = GachaLinkFetcher.check(ctx);
                lastShizukuState = st;
                runOnUi(() -> handleShizukuState(st));
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
                runOnUi(() -> setStatus("❌ 自动获取失败：" + msg
                        + "\n可改用「如何获取链接」里的手动方案。"));
            }
        }, "HyVqShizukuCheck").start();
    }

    /** 在主线程根据 Shizuku 状态分流处理 */
    private void handleShizukuState(final GachaLinkFetcher.State st) {
        final GachaLinkFetcher.State s =
                (st == null ? GachaLinkFetcher.State.NOT_RUNNING : st);
        switch (s) {
            case NOT_INSTALLED:
                showShizukuGuide(st);
                break;
            case NOT_RUNNING:
                showShizukuGuide(st);
                break;
            case NO_PERMISSION:
                setStatus("正在申请 Shizuku 授权…");
                // 同样是 Binder 调用，必须离开主线程
                new Thread(() -> {
                    try {
                        GachaLinkFetcher.removePermissionListener(permListener());
                        GachaLinkFetcher.addPermissionListener(permListener());
                        GachaLinkFetcher.requestPermission(
                                GachaLinkFetcher.REQ_SHIZUKU_PERMISSION);
                    } catch (Throwable t) {
                        final String msg = String.valueOf(t.getMessage());
                        runOnUi(() -> setStatus("❌ 申请授权失败：" + msg));
                    }
                }, "HyVqShizukuReq").start();
                break;
            case READY:
            default:
                setStatus("正在读取系统日志…");
                runScan();
                break;
        }
    }

    /** 状态不满足时给出引导弹窗 */
    private void showShizukuGuide(GachaLinkFetcher.State st) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "需要 Shizuku"));

        TextView tv = new TextView(ctx);
        tv.setText(GachaLinkFetcher.describe(st) + "\n\n"
                + "为什么要它：读取系统日志需要 adb(shell) 权限，而 Shizuku 能在"
                + "不 root 的前提下把这个权限授予应用，且可随时在 Shizuku 里撤销。\n\n"
                + "若不想使用，也可以走「如何获取链接」里的手动方案。");
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        tv.setLineSpacing(dp(2), 1.3f);
        tv.setPadding(dp(4), dp(6), dp(4), dp(4));
        box.addView(tv);

        final android.app.Dialog d = ModuleUiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        box.addView(btns, blp);

        if (st == GachaLinkFetcher.State.NOT_INSTALLED) {
            TextView open = btn("去安装", color(com.google.android.material.R.attr.colorOnPrimary),
                    color(com.google.android.material.R.attr.colorPrimary));
            open.setOnClickListener(v -> {
                ModuleUiKit.dismissWithAnim(d);
                try {
                    // 优先跳应用商店详情页
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://details?id=moe.shizuku.privileged.api"));
                    ctx.startActivity(i);
                } catch (Throwable t) {
                    try {
                        ctx.startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://shizuku.rikka.app/")));
                    } catch (Throwable t2) {
                        Toast.makeText(ctx, "请手动搜索安装 Shizuku", Toast.LENGTH_LONG).show();
                    }
                }
            });
            btns.addView(open);
        }
        TextView close = btn("知道了", color(com.google.android.material.R.attr.colorPrimary),
                color(com.google.android.material.R.attr.colorSurfaceContainerHighest));
        close.setOnClickListener(v -> ModuleUiKit.dismissWithAnim(d));
        btns.addView(close);
        d.show();
    }

    /** 执行扫描并把结果填进输入框 */
    private void runScan() {
        GachaLinkFetcher.scan(ctx, 8000, (best, all, err) -> {
            if (err != null) {
                setStatus("❌ " + err);
                return;
            }
            if (all == null || all.isEmpty()) {
                setStatus("未在日志中找到抽卡链接。\n"
                        + "请先在游戏里打开「祈愿 → 历史记录」，再回来点「自动获取」。");
                return;
            }
            // 填入输入框（追加，便于多条链接融合）
            StringBuilder sb = new StringBuilder();
            for (String u : all) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(u);
            }
            String cur = input.getText().toString().trim();
            input.setText(cur.isEmpty() ? sb.toString() : cur + "\n" + sb);
            setStatus("✅ 从日志中找到 " + all.size() + " 条链接，已填入输入框，"
                    + "点「解析并导入」即可。");
        });
    }

    /**
     * 「如何获取抽卡链接」教程弹窗。
     *
     * <p>链接里最关键的是 authkey —— 游戏打开「祈愿 → 历史记录」时会把它拼进网页地址，
     * 所以只要能截到这个 URL 就行。按设备与门槛给出三种方案。</p>
     */
    private void showHowToDialog() {
        ScrollView sv = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(box);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.66)));

        box.addView(ModuleUiKit.sectionHeader(ctx, "如何获取抽卡链接"));

        TextView intro = new TextView(ctx);
        intro.setText("链接里最关键的是 authkey。游戏打开「祈愿 → 历史记录」时会把含 authkey 的"
                + "网址交给网页组件，所以只要能截到这个网址就能分析记录。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        intro.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        intro.setLineSpacing(dp(2), 1.25f);
        intro.setPadding(dp(4), dp(2), dp(4), dp(6));
        box.addView(intro);

        addHowToSection(box, "方案一 · 电脑端读日志（最简单）",
                com.google.android.material.R.attr.colorPrimary,
                new String[]{
                        "① 电脑上打开原神，进入「祈愿 → 历史记录」并保持窗口不要关闭",
                        "② 找到游戏日志文件：",
                        "    C:\\Users\\<你的用户名>\\AppData\\LocalLow\\miHoYo\\原神\\output_log.txt",
                        "③ 用记事本打开，搜索 webstatic.mihoyo.com",
                        "④ 把找到的那一整行网址复制下来（要包含 authkey= 到最后）",
                        "⑤ 发送到手机，粘贴到上面的输入框即可",
                });

        addHowToSection(box, "方案二 · 手机端抓包",
                com.google.android.material.R.attr.colorSecondary,
                new String[]{
                        "① 安装抓包工具（如 Reqable、HttpCanary）",
                        "② 按提示安装 CA 证书并开启抓包",
                        "③ 在游戏里打开「祈愿 → 历史记录」",
                        "④ 在抓包记录里找到 webstatic.mihoyo.com 的请求",
                        "⑤ 复制完整网址，粘贴到上面的输入框",
                });

        addHowToSection(box, "方案三 · 电脑上直接用现成工具",
                com.google.android.material.R.attr.colorTertiary,
                new String[]{
                        "① 使用开源工具 Genshin Wish Export（GitHub 可搜）",
                        "② 它会自动从游戏日志或代理读取链接",
                        "③ 复制它读到的链接，发送到手机粘贴即可",
                });

        TextView tail = new TextView(ctx);
        tail.setText("💡 提示：authkey 有效期通常不到 24 小时，过期后重新获取即可。\n"
                + "本应用不会上传任何数据，记录只保存在本机。\n"
                + "服务端只保留最近 6 个月的记录，建议每隔一两个月导入一次以免丢失。");
        tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tail.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tail.setLineSpacing(dp(2), 1.3f);
        tail.setPadding(dp(4), dp(12), dp(4), dp(4));
        box.addView(tail);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(ctx, sv);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        box.addView(btns, blp);
        TextView close = btn("知道了", color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        close.setOnClickListener(v -> ModuleUiKit.dismissWithAnim(dialog));
        btns.addView(close);
        dialog.show();
    }

    /** 教程弹窗里的一个方案板块 */
    private void addHowToSection(LinearLayout parent, String title, int colorAttr, String[] lines) {
        LinearLayout sec = new LinearLayout(ctx);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setPadding(dp(12), dp(10), dp(12), dp(10));
        sec.setBackground(ModuleUiKit.rounded(ctx, 12,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        sec.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color(colorAttr));
        sec.addView(t);

        for (String ln : lines) {
            TextView row = new TextView(ctx);
            row.setText(ln);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            row.setLineSpacing(dp(3), 1.2f);
            row.setPadding(0, dp(2), 0, dp(2));
            // 路径这类内容用等宽字体，便于辨认
            if (ln.startsWith("    ")) row.setTypeface(Typeface.MONOSPACE);
            sec.addView(row);
        }
        parent.addView(sec);
    }

    private void showStats(String uid) {
        currentUid = uid;
        List<GachaRecord> records = GachaStore.load(ctx, uid);
        if (records.isEmpty()) {
            resultCard.setVisibility(GONE);
            setStatus("UID " + uid + " 暂无记录");
            return;
        }
        GachaStats.Result st = GachaStats.compute(records);

        poolBox.removeAllViews();

        // ── 总览 ──
        TextView h = new TextView(ctx);
        h.setText("UID " + uid);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        h.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        h.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        poolBox.addView(h);

        TextView range = new TextView(ctx);
        range.setText("记录区间：" + shortTime(st.firstTime) + " ～ " + shortTime(st.lastTime)
                + "　共 " + st.totalPulls + " 抽");
        range.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        range.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        range.setPadding(0, dp(2), 0, dp(10));
        poolBox.addView(range);

        LinearLayout sumRow = new LinearLayout(ctx);
        sumRow.setOrientation(HORIZONTAL);
        sumRow.addView(summaryChip("总抽数", String.valueOf(st.totalPulls),
                color(com.google.android.material.R.attr.colorPrimary)));
        sumRow.addView(summaryChip("五星", String.valueOf(st.total5),
                color(com.google.android.material.R.attr.colorError)));
        sumRow.addView(summaryChip("四星", String.valueOf(st.total4),
                color(com.google.android.material.R.attr.colorSecondary)));
        poolBox.addView(sumRow);

        // ── 未知卡池提示（官方清单为 100/200/301/302/400/500/5001） ──
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String t : st.allTypes) {
            if (!"100".equals(t) && !"200".equals(t) && !"301".equals(t) && !"302".equals(t)
                    && !"400".equals(t) && !"500".equals(t) && !"5001".equals(t)) {
                unknown.add(t);
            }
        }
        if (!unknown.isEmpty()) {
            TextView warn = new TextView(ctx);
            warn.setText("⚠️ 检测到官方清单之外的卡池类型：" + unknown
                    + "\n已按原样单独统计，如果数值异常请反馈该类型号。");
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            warn.setTextColor(color(com.google.android.material.R.attr.colorError));
            warn.setLineSpacing(dp(2), 1.25f);
            warn.setPadding(0, dp(6), 0, dp(4));
            poolBox.addView(warn);
        }

        // ── 各卡池 ──
        for (GachaStats.PoolStat ps : st.pools) {
            poolBox.addView(poolSection(ps));
        }

        resultCard.setVisibility(VISIBLE);
        setStatus(null);
    }

    private View summaryChip(String label, String value, int color) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setBackground(ModuleUiKit.rounded(ctx, 12,
                color(com.google.android.material.R.attr.colorSurfaceContainerHighest), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setTextColor(color);
        c.addView(v);

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        l.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        c.addView(l);
        return c;
    }

    private View poolSection(final GachaStats.PoolStat ps) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setBackground(ModuleUiKit.rounded(ctx, 12,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);

        TextView name = new TextView(ctx);
        name.setText(ps.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        c.addView(name);

        TextView meta = new TextView(ctx);
        StringBuilder mt = new StringBuilder();
        mt.append(ps.total).append(" 抽　五星 ").append(ps.count5)
          .append("　四星 ").append(ps.count4)
          .append("\n当前垫抽 ").append(ps.pity)
          .append("　平均出货 ").append(ps.avg5Text()).append(" 抽");
        // 组内包含多个池时列出各自抽数（301+400 / 500+5001 共享保底）
        String bd = ps.subBreakdown();
        if (!bd.isEmpty()) mt.append("\n构成：").append(bd);
        meta.setText(mt.toString());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        meta.setLineSpacing(dp(2), 1.2f);
        meta.setPadding(0, dp(6), 0, dp(4));
        c.addView(meta);

        // 五星明细（最近 8 个，倒序）
        if (!ps.fiveStars.isEmpty()) {
            List<GachaStats.FiveStar> fs = new ArrayList<>(ps.fiveStars);
            java.util.Collections.reverse(fs);
            int show = Math.min(8, fs.size());
            StringBuilder sb = new StringBuilder();
            boolean multi = ps.subTypes.size() > 1;
            for (int i = 0; i < show; i++) {
                GachaStats.FiveStar f = fs.get(i);
                sb.append("★ ").append(f.name)
                  .append("　第 ").append(f.pulls).append(" 抽")
                  .append(f.weapon ? "（武器）" : "");
                if (multi) sb.append("　").append(f.poolName());   // 标明出自哪个池
                sb.append("　").append(shortTime(f.time)).append("\n");
            }
            if (fs.size() > show) sb.append("… 共 ").append(fs.size()).append(" 个五星");
            TextView fl = new TextView(ctx);
            fl.setText(sb.toString().trim());
            fl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            fl.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            fl.setLineSpacing(dp(3), 1.2f);
            fl.setPadding(0, dp(6), 0, 0);
            c.addView(fl);
        }
        return c;
    }

    // ══════════════════════════════════════════════
    //  米游社账号登录 → 生成 authkey
    // ══════════════════════════════════════════════

    /**
     * 打开米游社登录（WebView）。
     *
     * <p>用网页登录而非自建登录接口，是因为后者需要 DS 动态签名（salt 随版本失效）、
     * 设备指纹与新设备短信验证；而官方网页本身已经处理了这一切，
     * 我们只需从 CookieManager 读取登录后的 Cookie。</p>
     */
    private void showMiyousheLogin() {
        if (!(ctx instanceof android.app.Activity)) {
            setStatus("❌ 无法打开登录页");
            return;
        }
        final android.app.Activity act = (android.app.Activity) ctx;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tip = new TextView(ctx);
        tip.setText("请在下方页面登录米游社账号。账号密码只提交给官方网页，本应用不接触。"
                + "登录成功后会自动返回并生成链接。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tip.setLineSpacing(dp(2), 1.25f);
        tip.setPadding(dp(4), dp(2), dp(4), dp(8));
        box.addView(tip);

        final android.webkit.WebView web = new android.webkit.WebView(act);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.58));
        box.addView(web, wlp);

        final TextView status2 = new TextView(ctx);
        status2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status2.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        status2.setPadding(dp(4), dp(8), dp(4), 0);
        status2.setText("等待登录…");
        box.addView(status2);

        final android.app.Dialog dialog = ModuleUiKit.glassDialog(ctx, box);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        box.addView(btns, blp);
        TextView cancel = btn("取消", color(com.google.android.material.R.attr.colorPrimary),
                color(com.google.android.material.R.attr.colorSurfaceContainerHighest));
        cancel.setOnClickListener(v -> {
            web.destroy();
            ModuleUiKit.dismissWithAnim(dialog);
        });
        btns.addView(cancel);

        MiyousheAuth.setupWebView(web, act, (cookie, err) -> {
            if (err != null) {
                status2.setText("❌ 登录失败：" + err);
                return;
            }
            status2.setText("✅ 已检测到登录态，正在验证…\nCookie 字段："
                    + MiyousheAuth.summarize(cookie));
            // 网络请求放子线程；先查角色验证 Cookie，再生成 authkey
            new Thread(() -> {
                String roles = MiyousheAuth.queryRoles(cookie);
                MiyousheAuth.AuthKeyResult r = MiyousheAuth.genAuthKey(cookie, "hk4e_cn");
                runOnUi(() -> {
                    try { web.destroy(); } catch (Throwable ignored) { }
                    ModuleUiKit.dismissWithAnim(dialog);
                    if (r.authKey != null && !r.authKey.isEmpty()) {
                        // 拼成与游戏一致的完整链接，便于复用既有解析逻辑
                        String url = "https://webstatic.mihoyo.com/hk4e/event/e20190909gacha-df01aea2/"
                                + "index.html?authkey_ver=1&sign_type=2&auth_appid=webview_gacha"
                                + "&lang=zh-cn&game_biz=hk4e_cn&authkey=" + r.authKey + "#/log";
                        String cur = input.getText().toString().trim();
                        input.setText(cur.isEmpty() ? url : cur + "\n" + url);
                        setStatus("✅ 已通过米游社登录获取链接，点「解析并导入」开始统计。");
                    } else {
                        // 分步展示，便于判断卡在哪一环
                        setStatus("❌ 生成链接失败：" + r.error
                                + "\n① 角色查询：" + summarizeRoles(roles)
                                + "\n② genAuthKey 返回：" + abbreviate(r.raw, 200));
                    }
                });
            }, "HyVqGenAuthKey").start();
        });

        dialog.show();
    }

    /** 从角色接口返回里提取「是否成功 / 角色数 / 错误」，用于分步排错 */
    private static String summarizeRoles(String json) {
        if (json == null || json.isEmpty()) return "无响应";
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            int code = o.optInt("retcode", 0);
            if (code != 0) {
                return "retcode " + code + " " + o.optString("message", "");
            }
            org.json.JSONArray list = o.optJSONObject("data") == null ? null
                    : o.optJSONObject("data").optJSONArray("list");
            int n = list == null ? 0 : list.length();
            StringBuilder sb = new StringBuilder("成功，找到 " + n + " 个角色");
            if (n > 0) {
                org.json.JSONObject r0 = list.optJSONObject(0);
                if (r0 != null) sb.append("（如 ").append(r0.optString("nickname", "?"))
                        .append(" / uid ").append(r0.optString("game_uid", "?"))
                        .append(" / ").append(r0.optString("region", "?")).append("）");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "解析失败：" + abbreviate(json, 90);
        }
    }

    /** 截断过长文本用于界面显示 */
    private static String abbreviate(String s, int max) {
        if (s == null) return "（空）";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // GachaView 常驻实例，这里只在真正脱离窗口时移除一次性回调
        try {
            if (permListenerObj != null) {
                GachaLinkFetcher.removePermissionListener(permListener());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String shortTime(String t) {
        if (t == null || t.length() < 10) return t == null ? "" : t;
        return t.substring(0, 10);
    }
}
