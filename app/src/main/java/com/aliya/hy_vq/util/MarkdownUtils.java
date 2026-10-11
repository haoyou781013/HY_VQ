package com.aliya.hy_vq.util;

/**
 * Markdown 渲染工具（零第三方依赖，手写解析）。
 *
 * <p>供<b>检查更新</b>的版本详情弹窗（GitHub Release notes）与<b>文件管理</b>的
 * md 预览共用。渲染目标为 <b>GitHub Flavored Markdown</b> 子集。
 */
public final class MarkdownUtils {

    /** HTML 实体占位符起始符：用私有码位区，escape 内部使用，不会出现在正常文本里 */
    private static final char ESC_MARK = '\uE000';

    /** {@link #ESC_MARK} 的字符串形式，供 {@code String.replace} 使用 */
    private static final String ESC_MARK_STR = String.valueOf(ESC_MARK);

    private MarkdownUtils() {
    }

    public static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        // GFM：用户输入的 HTML 实体（&amp; &copy; &#169; &nbsp; …）应原样保留，
        // 不能被 escape() 再转义成 &amp;amp;（那样页面上会显示字面量 "&amp;"）。
        // 做法：先把合法实体换成私有码位占位符 → 做标准转义 → 再把占位符换回 &。
        if (s.indexOf('&') < 0) {
            return s.replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int i = 0;
        int n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '&') {
                // 实体必须以 ';' 结尾，且长度受控（&name; / &#123; / &#xAB;）
                int semi = s.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 10 && isEntityBody(s, i + 1, semi)) {
                    // 合法实体 → 占位符（保留原文，稍后原样还原）
                    sb.append(ESC_MARK).append(s, i + 1, semi).append(';');
                    i = semi + 1;
                    continue;
                }
                // 裸 & 不是实体 → 直接转义成 &amp;（必须在这里转义：
                // 最后那步 replace 只还原占位符，管不到它）
                sb.append("&amp;");
                i++;
                continue;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString()
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                // 占位符此时仍未被上面的替换污染，还原成原始实体
                .replace(ESC_MARK_STR, "&");
    }

    /**
     * 判断 {@code s[from, to)} 是否是一个合法的实体名（不含首尾的 & 与 ;）。
     *
     * <p>接受 {@code amp} / {@code copy} 等命名实体，以及 {@code #169} /
     * {@code #x1F600} 这两种数字实体。</p>
     */
    private static boolean isEntityBody(String s, int from, int to) {
        if (from >= to) return false;
        if (s.charAt(from) == '#') {
            int i = from + 1;
            if (i < to && (s.charAt(i) == 'x' || s.charAt(i) == 'X')) {
                i++;
                if (i >= to) return false;
                for (; i < to; i++) {
                    char c = s.charAt(i);
                    boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                            || (c >= 'A' && c <= 'F');
                    if (!hex) return false;
                }
                return true;
            }
            for (; i < to; i++) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            return true;
        }
        // 命名实体：字母数字即可（不维护白名单，浏览器自己兜底）
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }

    // ══════════════ 行内语法 ══════════════

    /**
     * 行内 Markdown 渲染。先 escape 再替换语法标记。
     * 顺序敏感：先处理代码段（内部不解析），再处理加粗/斜体，最后链接/图片。
     */
    public static String inline(String s) {
        if (s == null || s.isEmpty()) return "";
        // 先提取行内代码，避免代码内容被其他规则误伤
        String[] parts = s.split("`");
        if (parts.length > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i % 2 == 1) {
                    sb.append("<code>").append(escape(parts[i])).append("</code>");
                } else {
                    sb.append(renderInlineText(parts[i]));
                }
            }
            return sb.toString();
        }
        return renderInlineText(s);
    }

    private static String renderInlineText(String s) {
        s = escape(s);
        // 图片（在链接之前，避免 ![] 被 [] 规则吃掉）
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)",
                "<img src=\"$2\" alt=\"$1\" style=\"max-width:100%\">");
        // 链接
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)",
                "<a href=\"$2\">$1</a>");
        // 粗体（双星/双下划线）
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("__(.+?)__", "<b>$1</b>");
        // 斜体（单星/单下划线，要求非空白包裹）
        s = s.replaceAll("(?<!\\*)\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)", "<i>$1</i>");
        s = s.replaceAll("(?<!_)_(?!\\s)(.+?)(?<!\\s)_(?!_)", "<i>$1</i>");
        // 删除线
        s = s.replaceAll("~~(.+?)~~", "<del>$1</del>");
        // 自动链接 <https://…>
        s = s.replaceAll("&lt;(https?://[^&\\s]+)&gt;", "<a href=\"$1\">$1</a>");
        // 裸 URL（不在已有标签内时简化处理）
        s = s.replaceAll("(?<!href=\")(?<!>)(https?://[^\\s<]+(?!.*<))",
                "<a href=\"$1\">$1</a>");
        return s;
    }

    // ══════════════ 块级语法 ══════════════

    /**
     * Markdown → HTML 完整转换。
     *
     * @param md 原始 Markdown 文本
     * @return HTML 片段（不含 html/head/body 外壳）
     */
    public static String toHtml(String md) {
        if (md == null || md.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        boolean inTable = false;
        boolean inList = false;       // <ul>/<ol> 是否已打开
        String[] tableAlign = null;   // GFM：表格分隔行给出的逐列对齐方式
        int tableRows = 0;            // 表格已渲染行数（0 → 下一行是表头）
        String pendingRow = null;      // 表格首行缓存（表头在分隔行之上，需延迟判定）
        boolean inOrderedList = false;
        boolean inBlockquote = false;
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int prevHeadingLevel = 0;     // Setext 标题：上一行是文本、本行是 ===/---

        for (int li = 0; li < lines.length; li++) {
            String raw = lines[li];
            String t = raw.trim();

            // ── 围栏代码块 ──
            if (t.startsWith("```") || t.startsWith("~~~")) {
                if (!inCode) {
                    inCode = true;
                    closeList(out, inList, inOrderedList); inList = false;
                    closeTable(out, inTable); inTable = false;
                    closeBlockquote(out, inBlockquote); inBlockquote = false;
                    // 提取语言标注（```java）
                    String lang = t.length() > 3 ? t.substring(3).trim() : "";
                    out.append("<pre>");
                    if (!lang.isEmpty()) {
                        out.append("<code class=\"language-")
                           .append(escape(lang)).append("\">");
                    } else {
                        out.append("<code>");
                    }
                } else {
                    inCode = false;
                    out.append("</code></pre>\n");
                }
                continue;
            }
            if (inCode) {
                out.append(escape(raw)).append('\n');
                continue;
            }

            // ── 空行：关闭所有打开的块，标记段落分隔 ──
            if (t.isEmpty()) {
                closeList(out, inList, inOrderedList); inList = false; inOrderedList = false;
                closeTable(out, inTable); inTable = false;
                closeBlockquote(out, inBlockquote); inBlockquote = false;
                prevHeadingLevel = 0;
                out.append("<br>\n");
                continue;
            }

            // ── Setext 标题（上一行是文本，本行是 === 或 ---）──
            if (prevHeadingLevel > 0) {
                if (t.matches("={3,}")) {
                    out.setLength(out.length() - 5); // 移除 "<p>\n"
                    out.append("<h1>").append("</h1>\n"); // 内容已被上一轮输出为 <p>
                    prevHeadingLevel = 0;
                    continue;
                }
                // 不是 Setext → 正常处理本行
                prevHeadingLevel = 0;
            }

// ── 表格 ──
            if (t.startsWith("|") && t.endsWith("|") && t.length() >= 2) {
                String[] cells = t.substring(1, t.length() - 1).split("\\|", -1);
                boolean isSep = cells.length > 0;
                for (String c : cells) {
                    if (!c.trim().matches(":?-+:?")) { isSep = false; break; }
                }
                if (isSep) {
                    // GFM：分隔行不渲染成数据，而是记录逐列对齐方式
                    // （':--' 左 / '--:' 右 / ':--:' 居中 / '--' 默认左），
                    // 并把「暂存的首行」回填成 <th> 表头（表头在分隔行之上）。
                    tableAlign = new String[cells.length];
                    for (int ci = 0; ci < cells.length; ci++) {
                        String d = cells[ci].trim();
                        boolean l = d.startsWith(":");
                        boolean r = d.endsWith(":");
                        tableAlign[ci] = (l && r) ? "center" : (r ? "right" : "left");
                    }
                    if (!inTable) {
                        inTable = true;
                        out.append("<table>\n");
                        // 首行暂存在 pendingRow 里 —— 现在能确定它是表头了
                        if (pendingRow != null) {
                            String[] hc = pendingRow.substring(1, pendingRow.length() - 1).split("\\|", -1);
                            out.append("<tr>");
                            for (int ci = 0; ci < hc.length; ci++) {
                                String al = (ci < tableAlign.length)
                                        ? " align=\"" + tableAlign[ci] + "\"" : "";
                                out.append("<th" + al + ">")
                                        .append(inline(hc[ci].trim())).append("</th>");
                            }
                            out.append("</tr>\n");
                            pendingRow = null;
                            tableRows = 1;      // 表头已占一行
                        }
                    }
                    continue;
                }
                closeList(out, inList, inOrderedList); inList = false; inOrderedList = false;
                closeBlockquote(out, inBlockquote); inBlockquote = false;
                // GFM：表头在分隔行「之上」→ 第一行先暂存，等分隔行来确认。
                // 若整块都没有分隔行（|a| 后直接换行），它压根不是表格。
                if (!inTable && tableAlign == null) {
                    pendingRow = t;
                    continue;
                }
                if (!inTable) {
                    inTable = true;
                    out.append("<table>\n");
                }
                boolean header = (tableRows == 0);
                tableRows++;
                out.append("<tr>");
                for (int ci = 0; ci < cells.length; ci++) {
                    String c = cells[ci];
                    // GFM：单元格里的 \| 是字面量竖线，不能当列分隔符
                    c = c.replace("\\|", "|");
                    String al = (tableAlign != null && ci < tableAlign.length)
                            ? " align=\"" + tableAlign[ci] + "\"" : "";
                    String body = inline(c.trim());
                    out.append(header ? "<th" + al + ">" + body + "</th>"
                            : "<td" + al + ">" + body + "</td>");
                }
                out.append("</tr>\n");
                continue;
            }
            // 非表格行：把暂存的首行按普通段落补上（GFM 里这不是表格）
            if (pendingRow != null) {
                out.append("<p>").append(inline(pendingRow)).append("</p>\n");
                pendingRow = null;
            }
            if (inTable) { closeTable(out, inTable); inTable = false; tableAlign = null; tableRows = 0; }

            // ── ATX 标题 # ~ ###### ──
            int h = 0;
            while (h < t.length() && h < 6 && t.charAt(h) == '#') h++;
            if (h > 0 && h < t.length() && t.charAt(h) == ' ') {
                closeList(out, inList, inOrderedList); inList = false; inOrderedList = false;
                closeBlockquote(out, inBlockquote); inBlockquote = false;
                String text = t.substring(h + 1).replaceAll("\\s+#+\\s*$", ""); // 尾部 #
                out.append("<h").append(h).append(">").append(inline(text))
                   .append("</h").append(h).append(">\n");
                continue;
            }

            // ── 分隔线 ──
            if (t.matches("(-{3,}|\\*{3,}|_{3,})")) {
                closeList(out, inList, inOrderedList); inList = false; inOrderedList = false;
                closeBlockquote(out, inBlockquote); inBlockquote = false;
                out.append("<hr>\n");
                continue;
            }

            // ── 引用 ──
            if (t.startsWith(">")) {
                closeList(out, inList, inOrderedList); inList = false; inOrderedList = false;
                if (!inBlockquote) {
                    inBlockquote = true;
                    out.append("<blockquote>");
                }
                out.append(inline(t.substring(1).trim())).append("<br>\n");
                continue;
            }
            if (inBlockquote) closeBlockquote(out, inBlockquote); inBlockquote = false;

            // ── 任务列表 ──
            if (t.matches("^[-*+]\\s+\\[[ xX]\\]\\s+.*")) {
                boolean checked = t.contains("[x]") || t.contains("[X]");
                String text = t.replaceAll("^[-*+]\\s+\\[[ xX]\\]\\s+", "");
                // GFM/HTML5：任务列表用真 <input type="checkbox">，与 GitHub 渲染一致。
                // disabled：只读预览里不可点（对齐 GitHub 预览态），外观完全相同。
                out.append("<li class=\"task\"><input type=\"checkbox\" disabled")
                        .append(checked ? " checked" : "")
                        .append("> ")
                        .append(inline(text)).append("</li>\n");
                continue;
            }

            // ── 无序列表 ──
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) {
                if (inOrderedList) closeList(out, true, true); inOrderedList = false;
                if (!inList) { inList = true; out.append("<ul>\n"); }
                out.append("<li>").append(inline(t.substring(2))).append("</li>\n");
                continue;
            }

            // ── 有序列表 ──
            if (t.matches("\\d+[.)]\\s+.*")) {
                if (inList) closeList(out, true, false); inList = false;
                // GFM：有序列表保留 markdown 里的起始序号（"3. c" 渲染成从 3 开始）
                int dot0 = t.indexOf('.');
                if (dot0 < 0) dot0 = t.indexOf(')');
                int startNo = 1;
                try {
                    startNo = Integer.parseInt(t.substring(0, dot0).trim());
                } catch (Throwable ignore) {
                    // 数字异常回落到默认 1
                }
                if (!inOrderedList) {
                    inOrderedList = true;
                    if (startNo != 1) out.append("<ol start=\"").append(startNo).append("\">\n");
                    else out.append("<ol>\n");
                }
                int dot = t.indexOf('.');
                if (dot < 0) dot = t.indexOf(')');
                out.append("<li>").append(inline(t.substring(dot + 1).trim())).append("</li>\n");
                continue;
            }

            // ── 连续列表项关闭 ──
            if (inList) closeList(out, inList, inOrderedList);
            inList = false; inOrderedList = false;

            // ── 普通段落 ──
            // 检测是否是 Setext 标题的候选（普通文本行，下一行可能是 ===/---）
            if (li + 1 < lines.length) {
                String next = lines[li + 1].trim();
                if (next.matches("={3,}") || (next.matches("-{3,}") && !t.startsWith("-"))) {
                    prevHeadingLevel = h > 0 ? h : 1;
                    // 先按普通段落输出，Setext 检测在下一轮修正
                    out.append("<p>").append(inline(t)).append("</p>\n");
                    continue;
                }
            }
            // 换行：行尾两个空格
            boolean hardBreak = raw.endsWith("  ") || raw.endsWith("\\");
            out.append("<p>").append(inline(t));
            if (hardBreak) out.append("<br>");
            out.append("</p>\n");
        }
        if (inCode) out.append("</code></pre>\n");
        closeTable(out, inTable);
        closeList(out, inList, inOrderedList);
        closeBlockquote(out, inBlockquote);
        // 收尾：输入结束时若还有暂存的表格首行，它没有分隔行 → 不是表格，按段落输出
        if (pendingRow != null) {
            out.append("<p>").append(inline(pendingRow)).append("</p>\n");
            pendingRow = null;
        }
        return out.toString();
    }

    // ══════════════ 完整 HTML 文档（供 WebView 直接加载） ══════════════

    /**
     * 生成可直接加载进 WebView 的完整 HTML 文档。
     *
     * @param md        Markdown 源文
     * @param cssBody   附加 CSS（可为 null）
     * @param lightMode true = 浅色主题
     */
    public static String toDocument(String md, String cssBody, boolean lightMode) {
        String bg = lightMode ? "#ffffff" : "#1e1e1e";
        String fg = lightMode ? "#24292f" : "#d4d4d4";
        String codeBg = lightMode ? "#f6f8fa" : "#2d2d2d";
        String border = lightMode ? "#d0d7de" : "#444444";
        String quote = lightMode ? "#57606a" : "#8b949e";
        String link = lightMode ? "#0969da" : "#58a6ff";

        String css = "<style>"
                + "*{box-sizing:border-box;}"
                + "body{font-family:sans-serif;padding:6px 10px;line-height:1.4;"
                + "font-size:13px;color:" + fg + ";background:" + bg + ";word-wrap:break-word;}"
                + "h1,h2,h3,h4,h5,h6{margin:6px 0 2px;line-height:1.2;}"
                + "h1{font-size:1.3em;border-bottom:1px solid " + border + ";padding-bottom:1px;}"
                + "h2{font-size:1.15em;border-bottom:1px solid " + border + ";padding-bottom:1px;}"
                + "h3{font-size:1.05em;}h4{font-size:1.0em;}"
                + "p{margin:2px 0;}"
                + "ul,ol{margin:2px 0;padding-left:16px;}"
                + "li{margin:0;}"
                + "li.task{list-style:none;margin-left:-14px;}"
                + "code{background:" + codeBg + ";padding:0 3px;border-radius:3px;"
                + "font-family:monospace;font-size:0.88em;}"
                + "pre{background:" + codeBg + ";padding:4px 6px;border-radius:4px;overflow-x:auto;margin:3px 0;}"
                + "pre code{background:transparent;padding:0;font-size:0.82em;}"
                + "blockquote{border-left:3px solid " + quote + ";margin:3px 0;"
                + "padding:1px 6px;color:" + quote + ";}"
                + "table{border-collapse:collapse;margin:3px 0;min-width:50%;}"
                + "td,th{border:1px solid " + border + ";padding:2px 6px;text-align:left;}"
                + "th{background:" + codeBg + ";font-weight:bold;}"
                + "hr{border:none;border-top:1px solid " + border + ";margin:6px 0;}"
                + "a{color:" + link + ";text-decoration:none;}"
                + "a:hover{text-decoration:underline;}"
                + "img{max-width:100%;border-radius:4px;}"
                + (cssBody != null ? cssBody : "")
                + "</style>";

        return "<!DOCTYPE html><html><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + css + "</head><body>"
                + toHtml(md) + "</body></html>";
    }

    // ══════════════ 内部：关闭块 ══════════════

    private static void closeTable(StringBuilder sb, boolean open) {
        if (open) sb.append("</table>\n");
    }

    private static void closeList(StringBuilder sb, boolean inUl, boolean inOl) {
        if (inUl && !inOl) sb.append("</ul>\n");
        if (inOl && !inUl) sb.append("</ol>\n");
        if (inUl && inOl) sb.append("</ul></ol>\n"); // 不应发生，防御
    }

    private static void closeBlockquote(StringBuilder sb, boolean open) {
        if (open) sb.append("</blockquote>\n");
    }
}
