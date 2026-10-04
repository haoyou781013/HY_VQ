package com.aliya.hy_vq.util;

/**
 * 共享的 Markdown → HTML 渲染器（无第三方库）。
 *
 * <p>供「更新详情页」与「文件管理预览」共用，确保两处渲染一致。</p>
 *
 * <h3>支持的语法</h3>
 * <pre>
 * 标题 #~###### / Setext（=== ---）
 * 粗体 **x** __x__ / 斜体 *x* _x_ / 删除线 ~~x~~
 * 行内代码 `x` / 围栏代码块 ```lang … ```
 * 无序列表 - * + / 有序列表 1. / 任务列表 - [ ] - [x]
 * 引用 > / 分隔线 --- *** ___ / 表格 | a | b |
 * 链接 [text](url) / 图片 ![alt](url) / 自动链接 <url> / 裸 URL
 * 换行（行尾两个空格）/ 段落分隔（空行）
 * </pre>
 *
 * <h3>编码处理</h3>
 * HTML 转义按标准顺序：先 &amp; 再 &lt; &gt; 再引号，避免双重转义。
 */
public final class MarkdownUtils {

    private MarkdownUtils() {
    }

    // ══════════════ HTML 转义 ══════════════

    /** HTML 实体转义（按标准顺序，避免双重转义） */
    public static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
                    if (!c.trim().matches(":?-{3,}:?")) { isSep = false; break; }
                }
                if (isSep) continue; // 跳过分隔行
                closeList(out, inList, inOrderedList); inList = false; inOrderedList = false;
                closeBlockquote(out, inBlockquote); inBlockquote = false;
                if (!inTable) {
                    inTable = true;
                    out.append("<table>\n");
                }
                out.append("<tr>");
                for (String c : cells) out.append("<td>").append(inline(c.trim())).append("</td>");
                out.append("</tr>\n");
                continue;
            }
            if (inTable) closeTable(out, inTable); inTable = false;

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
                out.append("<li class=\"task\">")
                   .append(checked ? "☑ " : "☐ ")
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
                if (!inOrderedList) { inOrderedList = true; out.append("<ol>\n"); }
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
                + "body{font-family:sans-serif;padding:10px 12px;line-height:1.5;"
                + "color:" + fg + ";background:" + bg + ";word-wrap:break-word;}"
                + "h1,h2,h3,h4,h5,h6{margin:10px 0 4px;line-height:1.25;}"
                + "h1{font-size:1.4em;border-bottom:1px solid " + border + ";padding-bottom:2px;}"
                + "h2{font-size:1.2em;border-bottom:1px solid " + border + ";padding-bottom:2px;}"
                + "h3{font-size:1.1em;}h4{font-size:1.0em;}"
                + "p{margin:3px 0;}"
                + "ul,ol{margin:3px 0;padding-left:18px;}"
                + "li{margin:1px 0;}"
                + "li.task{list-style:none;margin-left:-14px;}"
                + "code{background:" + codeBg + ";padding:0 4px;border-radius:3px;"
                + "font-family:monospace;font-size:0.9em;}"
                + "pre{background:" + codeBg + ";padding:6px 8px;border-radius:4px;overflow-x:auto;margin:4px 0;}"
                + "pre code{background:transparent;padding:0;font-size:0.85em;}"
                + "blockquote{border-left:3px solid " + quote + ";margin:4px 0;"
                + "padding:2px 8px;color:" + quote + ";}"
                + "table{border-collapse:collapse;margin:4px 0;min-width:50%;}"
                + "td,th{border:1px solid " + border + ";padding:3px 8px;text-align:left;}"
                + "th{background:" + codeBg + ";font-weight:bold;}"
                + "hr{border:none;border-top:1px solid " + border + ";margin:8px 0;}"
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
